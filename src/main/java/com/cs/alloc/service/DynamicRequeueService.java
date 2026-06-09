package com.cs.alloc.service;

import com.cs.alloc.domain.*;
import com.cs.alloc.mapper.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class DynamicRequeueService {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final QueueEntryMapper queueEntryMapper;
    private final SessionMapper sessionMapper;
    private final AgentMapper agentMapper;
    private final RequeueAuditMapper requeueAuditMapper;
    private final RedisService redisService;
    private final MessageQueue messageQueue;
    private final SlaRiskPredictor slaRiskPredictor;

    @Value("${cs.sla.fallback-skill-group-id:1}")
    private long fallbackSkillGroupId;

    public DynamicRequeueService(QueueEntryMapper queueEntryMapper, SessionMapper sessionMapper,
                                  AgentMapper agentMapper, RequeueAuditMapper requeueAuditMapper,
                                  RedisService redisService, MessageQueue messageQueue,
                                  SlaRiskPredictor slaRiskPredictor) {
        this.queueEntryMapper = queueEntryMapper;
        this.sessionMapper = sessionMapper;
        this.agentMapper = agentMapper;
        this.requeueAuditMapper = requeueAuditMapper;
        this.redisService = redisService;
        this.messageQueue = messageQueue;
        this.slaRiskPredictor = slaRiskPredictor;
    }

    /**
     * 人工置顶: 将指定会话提升到队列最前面。
     * 不影响已接入的会话, 仅调整排队中的队列顺序。
     */
    @Transactional
    public void pinTop(long sessionId, String operatorId) {
        QueueEntry entry = queueEntryMapper.selectBySessionId(sessionId);
        if (entry == null) {
            throw new com.cs.alloc.common.BizException("会话不在排队中");
        }

        int oldPosition = entry.getPosition();
        int oldPriority = entry.getPriorityScore();

        // 设置置顶标记
        queueEntryMapper.updatePinned(sessionId, true, operatorId);

        // 提升优先级到最高 (当前组内最高分 + 10000)
        List<QueueEntry> groupEntries = queueEntryMapper.selectBySkillGroupId(entry.getSkillGroupId());
        int maxPriority = groupEntries.stream().mapToInt(QueueEntry::getPriorityScore).max().orElse(0);
        int newPriority = maxPriority + 10000;
        queueEntryMapper.updatePriorityScore(sessionId, newPriority);
        redisService.updateQueuePriority(entry.getSkillGroupId(), sessionId, newPriority);

        // 重排位置
        reorderPositions(entry.getSkillGroupId());
        QueueEntry updated = queueEntryMapper.selectBySessionId(sessionId);

        // 审计日志
        recordAudit(sessionId, "PIN_TOP", operatorId, "SUPERVISOR", entry.getSkillGroupId(),
                oldPosition, updated.getPosition(), oldPriority, newPriority, null, null,
                String.format("人工置顶会话, 操作人: %s", operatorId));

        publishQueueReordered(entry.getSkillGroupId(), "PIN_TOP", sessionId);
        log.info("人工置顶: session={}, operator={}", sessionId, operatorId);
    }

    /**
     * 取消置顶: 恢复会话的正常排队优先级。
     */
    @Transactional
    public void unpinTop(long sessionId, String operatorId) {
        QueueEntry entry = queueEntryMapper.selectBySessionId(sessionId);
        if (entry == null) {
            throw new com.cs.alloc.common.BizException("会话不在排队中");
        }
        if (!Boolean.TRUE.equals(entry.getPinned())) {
            throw new com.cs.alloc.common.BizException("会话未置顶");
        }

        int oldPriority = entry.getPriorityScore();
        int oldPosition = entry.getPosition();

        queueEntryMapper.updatePinned(sessionId, false, null);

        // 恢复正常优先级
        long waitSeconds = java.time.temporal.ChronoUnit.SECONDS.between(entry.getJoinedAt(), LocalDateTime.now());
        Customer customer = null;
        Session session = sessionMapper.selectById(sessionId);
        int vipLevel = 0;
        if (session != null) {
            // 通过 session 获取 vipLevel 简化处理
            vipLevel = session.getPriorityScore() != null ? session.getPriorityScore() / 10 : 0;
        }
        int normalPriority = vipLevel * 10 + (int) waitSeconds;
        queueEntryMapper.updatePriorityScore(sessionId, normalPriority);
        redisService.updateQueuePriority(entry.getSkillGroupId(), sessionId, normalPriority);

        reorderPositions(entry.getSkillGroupId());
        QueueEntry updated = queueEntryMapper.selectBySessionId(sessionId);

        recordAudit(sessionId, "UNPIN", operatorId, "SUPERVISOR", entry.getSkillGroupId(),
                oldPosition, updated.getPosition(), oldPriority, normalPriority, null, null,
                String.format("取消置顶, 操作人: %s", operatorId));

        publishQueueReordered(entry.getSkillGroupId(), "UNPIN", sessionId);
        log.info("取消置顶: session={}, operator={}", sessionId, operatorId);
    }

    /**
     * VIP 插队: 根据 VIP 等级提升排队优先级。
     * VIP 等级越高, 插队幅度越大。
     */
    @Transactional
    public void vipJump(long sessionId, int vipLevel) {
        if (vipLevel <= 0) return;

        QueueEntry entry = queueEntryMapper.selectBySessionId(sessionId);
        if (entry == null) {
            throw new com.cs.alloc.common.BizException("会话不在排队中");
        }

        int oldPriority = entry.getPriorityScore();
        int oldPosition = entry.getPosition();

        // VIP 插队加权: vipLevel * 1000, 在普通优先级之上大幅提升
        int vipBonus = vipLevel * 1000;
        int newPriority = oldPriority + vipBonus;
        queueEntryMapper.updatePriorityScore(sessionId, newPriority);
        redisService.updateQueuePriority(entry.getSkillGroupId(), sessionId, newPriority);

        reorderPositions(entry.getSkillGroupId());
        QueueEntry updated = queueEntryMapper.selectBySessionId(sessionId);

        recordAudit(sessionId, "VIP_JUMP", null, "SYSTEM", entry.getSkillGroupId(),
                oldPosition, updated.getPosition(), oldPriority, newPriority, null, null,
                String.format("VIP插队, VIP等级: %d, 加权: %d", vipLevel, vipBonus));

        publishQueueReordered(entry.getSkillGroupId(), "VIP_JUMP", sessionId);
        log.info("VIP插队: session={}, vipLevel={}, priority {} -> {}", sessionId, vipLevel, oldPriority, newPriority);
    }

    /**
     * 技能组降级兜底: 当目标技能组无可用客服时, 降级到通用技能组。
     * 保留原始技能组 ID 以便后续恢复。
     */
    @Transactional
    public boolean skillFallback(long sessionId) {
        QueueEntry entry = queueEntryMapper.selectBySessionId(sessionId);
        if (entry == null) return false;

        // 已经降级过则不重复降级
        if (entry.getOriginalSkillGroupId() != null) return false;

        // 检查当前技能组是否有可用客服
        int available = slaRiskPredictor.countAvailableAgents(entry.getSkillGroupId());
        if (available > 0) return false;

        // 检查兜底技能组是否有可用客服
        if (entry.getSkillGroupId() == fallbackSkillGroupId) return false;
        int fallbackAvailable = slaRiskPredictor.countAvailableAgents(fallbackSkillGroupId);
        if (fallbackAvailable == 0) return false;

        long oldSkillGroupId = entry.getSkillGroupId();
        int oldPosition = entry.getPosition();
        String oldRiskLevel = entry.getRiskLevel();

        // 从原技能组 Redis 队列中移除
        redisService.removeFromQueue(oldSkillGroupId, sessionId);

        // 更新技能组
        queueEntryMapper.updateSkillGroupId(sessionId, fallbackSkillGroupId, oldSkillGroupId);

        // 同时更新 session 表的技能组
        sessionMapper.updateSkillGroupId(sessionId, fallbackSkillGroupId);

        // 加入新技能组 Redis 队列
        redisService.addToQueue(fallbackSkillGroupId, sessionId, entry.getPriorityScore());

        // 重排两个技能组的位置
        reorderPositions(oldSkillGroupId);
        reorderPositions(fallbackSkillGroupId);

        QueueEntry updated = queueEntryMapper.selectBySessionId(sessionId);

        recordAudit(sessionId, "SKILL_FALLBACK", null, "SYSTEM", fallbackSkillGroupId,
                oldPosition, updated.getPosition(), null, null, oldRiskLevel, null,
                String.format("技能组降级: %d -> %d, 原组无可用客服", oldSkillGroupId, fallbackSkillGroupId));

        publishQueueReordered(fallbackSkillGroupId, "SKILL_FALLBACK", sessionId);
        log.info("技能组降级: session={}, {} -> {}", sessionId, oldSkillGroupId, fallbackSkillGroupId);
        return true;
    }

    /**
     * 根据 SLA 风险分数重排队列。
     * 排序规则: 置顶优先 > 风险分数高优先 > 优先级分高优先 > 入队时间早优先。
     * 仅调整待分配队列, 不影响已接入会话。
     */
    @Transactional
    public void reorderByRisk(long skillGroupId) {
        List<QueueEntry> entries = queueEntryMapper.selectBySkillGroupId(skillGroupId);
        if (entries.isEmpty()) return;

        // 按规则排序: pinned 优先, 然后 riskScore 高优先, 然后 priorityScore 高优先, 最后 joinedAt 早优先
        entries.sort((a, b) -> {
            // 置顶优先
            boolean aPinned = Boolean.TRUE.equals(a.getPinned());
            boolean bPinned = Boolean.TRUE.equals(b.getPinned());
            if (aPinned != bPinned) return aPinned ? -1 : 1;

            // 风险分数高优先
            int aRisk = a.getRiskScore() != null ? a.getRiskScore() : 0;
            int bRisk = b.getRiskScore() != null ? b.getRiskScore() : 0;
            if (aRisk != bRisk) return Integer.compare(bRisk, aRisk);

            // 优先级分高优先
            int aPri = a.getPriorityScore() != null ? a.getPriorityScore() : 0;
            int bPri = b.getPriorityScore() != null ? b.getPriorityScore() : 0;
            if (aPri != bPri) return Integer.compare(bPri, aPri);

            // 入队时间早优先
            return a.getJoinedAt().compareTo(b.getJoinedAt());
        });

        // 更新位置和 Redis 优先级
        for (int i = 0; i < entries.size(); i++) {
            QueueEntry qe = entries.get(i);
            int newPosition = i + 1;
            // 重算综合分: 位置越靠前分越高
            int compositeScore = (entries.size() - i) * 100
                    + (qe.getRiskScore() != null ? qe.getRiskScore() : 0)
                    + (Boolean.TRUE.equals(qe.getPinned()) ? 100000 : 0);

            queueEntryMapper.updatePosition(qe.getSessionId(), newPosition);
            redisService.updateQueuePriority(skillGroupId, qe.getSessionId(), compositeScore);
        }

        log.debug("队列重排完成: skillGroupId={}, count={}", skillGroupId, entries.size());
    }

    /**
     * 根据优先级分数重排队列位置 (内部方法)。
     */
    void reorderPositions(long skillGroupId) {
        List<QueueEntry> entries = queueEntryMapper.selectBySkillGroupId(skillGroupId);
        for (int i = 0; i < entries.size(); i++) {
            queueEntryMapper.updatePosition(entries.get(i).getSessionId(), i + 1);
        }
    }

    /**
     * 查询重排审计日志。
     */
    public List<RequeueAuditLog> getAuditLogs(Long sessionId, String action, int page, int pageSize) {
        if (sessionId != null) {
            return requeueAuditMapper.selectBySessionId(sessionId);
        }
        int offset = (page - 1) * pageSize;
        if (action != null && !action.isEmpty()) {
            return requeueAuditMapper.selectByAction(action, offset, pageSize);
        }
        return requeueAuditMapper.selectRecent(offset, pageSize);
    }

    void recordAudit(long sessionId, String action, String operatorId, String operatorType,
                     Long skillGroupId, Integer oldPos, Integer newPos, Integer oldPri, Integer newPri,
                     String oldRisk, String newRisk, String detail) {
        RequeueAuditLog auditLog = new RequeueAuditLog();
        auditLog.setSessionId(sessionId);
        auditLog.setAction(action);
        auditLog.setOperatorId(operatorId);
        auditLog.setOperatorType(operatorType);
        auditLog.setSkillGroupId(skillGroupId);
        auditLog.setOldPosition(oldPos);
        auditLog.setNewPosition(newPos);
        auditLog.setOldPriority(oldPri);
        auditLog.setNewPriority(newPri);
        auditLog.setOldRiskLevel(oldRisk);
        auditLog.setNewRiskLevel(newRisk);
        auditLog.setDetail(detail);
        requeueAuditMapper.insert(auditLog);
    }

    private void publishQueueReordered(long skillGroupId, String action, long sessionId) {
        try {
            String msg = MAPPER.writeValueAsString(Map.of(
                    "skillGroupId", skillGroupId,
                    "action", action,
                    "sessionId", sessionId,
                    "timestamp", System.currentTimeMillis()
            ));
            messageQueue.publish(MessageQueue.Topics.QUEUE_REORDERED, msg);
        } catch (Exception e) {
            log.error("发布队列重排事件失败", e);
        }
    }
}
