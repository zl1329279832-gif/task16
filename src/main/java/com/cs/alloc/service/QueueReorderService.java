package com.cs.alloc.service;

import com.cs.alloc.common.BizException;
import com.cs.alloc.domain.AuditLog;
import com.cs.alloc.domain.QueueEntry;
import com.cs.alloc.domain.Session;
import com.cs.alloc.domain.SlaRiskScore;
import com.cs.alloc.mapper.AuditLogMapper;
import com.cs.alloc.mapper.QueueEntryMapper;
import com.cs.alloc.mapper.SessionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class QueueReorderService {
    private final QueueEntryMapper queueEntryMapper;
    private final AuditLogMapper auditLogMapper;
    private final RedisService redisService;
    private final SlaRiskCalculator slaRiskCalculator;
    private final MessageQueue messageQueue;
    private final SessionMapper sessionMapper;

    /**
     * 按风险评分重排指定技能组的队列。
     * 只影响 WAITING 状态的排队条目，不破坏已接入会话。
     * 同时清理 Redis 队列中残留的非 WAITING 会话。
     */
    @Transactional
    public boolean reorderQueueByRisk(long skillGroupId) {
        String lockKey = "reorder:sg:" + skillGroupId;
        String lockOwner = redisService.tryLock(lockKey, Duration.ofSeconds(10));
        if (lockOwner == null) {
            log.debug("技能组 {} 重排锁获取失败, 跳过本轮", skillGroupId);
            return false;
        }
        try {
            // calculateSkillGroupRisks 已内部只查 WAITING 状态会话
            Map<Long, SlaRiskScore> riskScores = slaRiskCalculator.calculateSkillGroupRisks(skillGroupId);
            if (riskScores.isEmpty()) return false;

            // 清理 Redis 中残留的非 WAITING 会话
            Set<String> redisMembers = redisService.getQueueMembers(skillGroupId);
            if (redisMembers != null) {
                Set<String> waitingIds = riskScores.keySet().stream()
                        .map(String::valueOf).collect(Collectors.toSet());
                int evicted = 0;
                for (String member : redisMembers) {
                    if (!waitingIds.contains(member)) {
                        redisService.removeFromQueue(skillGroupId, Long.parseLong(member));
                        evicted++;
                    }
                }
                if (evicted > 0) {
                    log.warn("技能组 {} 清理 {} 个非WAITING残留条目", skillGroupId, evicted);
                }
            }

            // Sort by risk score descending (highest risk first)
            List<Map.Entry<Long, SlaRiskScore>> sorted = riskScores.entrySet().stream()
                    .sorted((a, b) -> Double.compare(b.getValue().getRiskScore(), a.getValue().getRiskScore()))
                    .collect(Collectors.toList());

            // Update priority scores and positions in DB + Redis
            int position = 1;
            for (Map.Entry<Long, SlaRiskScore> entry : sorted) {
                long sessionId = entry.getKey();
                SlaRiskScore risk = entry.getValue();
                int intScore = risk.getRiskScore() == Double.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.round(risk.getRiskScore());
                queueEntryMapper.updatePriorityScore(sessionId, intScore);
                queueEntryMapper.updatePosition(sessionId, position);
                redisService.updateQueueEntryScore(skillGroupId, sessionId, risk.getRiskScore());
                position++;
            }

            // Publish reorder event
            messageQueue.publish(MessageQueue.Topics.QUEUE_REORDERED,
                    String.format("{\"skillGroupId\":%d,\"count\":%d,\"timestamp\":%d}",
                            skillGroupId, sorted.size(), System.currentTimeMillis()));

            // Audit log
            audit("SYSTEM", "SYSTEM", "QUEUE_REORDER", "SKILL_GROUP",
                    String.valueOf(skillGroupId),
                    String.format("按风险评分重排队列, 共%d个WAITING会话", sorted.size()));

            log.info("技能组 {} 队列重排完成, {} 个WAITING会话", skillGroupId, sorted.size());
            return true;
        } finally {
            redisService.unlock(lockKey, lockOwner);
        }
    }

    /**
     * 人工置顶: 将指定会话固定在队列最前面。
     * 仅允许对 WAITING 状态的会话操作。
     */
    @Transactional
    public void pinSession(long sessionId, String operatorId) {
        QueueEntry entry = queueEntryMapper.selectBySessionId(sessionId);
        if (entry == null) throw new BizException("会话不在排队中");
        assertSessionWaiting(sessionId);

        queueEntryMapper.updatePinned(sessionId, true);
        queueEntryMapper.updatePriorityScore(sessionId, Integer.MAX_VALUE);
        redisService.updateQueueEntryScore(entry.getSkillGroupId(), sessionId, Double.MAX_VALUE);
        redisService.setPinnedFlag(sessionId, Duration.ofSeconds(3600));

        audit(operatorId, "SUPERVISOR", "SESSION_PIN", "SESSION",
                String.valueOf(sessionId), "人工置顶");
        log.info("会话 {} 已置顶, 操作人: {}", sessionId, operatorId);
    }

    /**
     * 取消置顶。
     */
    @Transactional
    public void unpinSession(long sessionId, String operatorId) {
        QueueEntry entry = queueEntryMapper.selectBySessionId(sessionId);
        if (entry == null) throw new BizException("会话不在排队中");

        queueEntryMapper.updatePinned(sessionId, false);
        redisService.clearPinnedFlag(sessionId);

        audit(operatorId, "SUPERVISOR", "SESSION_UNPIN", "SESSION",
                String.valueOf(sessionId), "取消置顶");
        log.info("会话 {} 已取消置顶, 操作人: {}", sessionId, operatorId);
    }

    /**
     * VIP 插队: 为 VIP 客户一次性增加优先级分数。
     * 仅允许对 WAITING 状态的会话操作。
     */
    @Transactional
    public void applyVipJump(long sessionId, int vipLevel, String operatorId) {
        QueueEntry entry = queueEntryMapper.selectBySessionId(sessionId);
        if (entry == null) throw new BizException("会话不在排队中");
        assertSessionWaiting(sessionId);

        int bonus = vipLevel * 10;
        int newScore = (entry.getPriorityScore() != null ? entry.getPriorityScore() : 0) + bonus;
        queueEntryMapper.updatePriorityScore(sessionId, newScore);
        redisService.updateQueueEntryScore(entry.getSkillGroupId(), sessionId, newScore);

        audit(operatorId, "SYSTEM", "VIP_JUMP", "SESSION",
                String.valueOf(sessionId),
                String.format("VIP%d插队, 加分%d, 新分数%d", vipLevel, bonus, newScore));
        log.info("会话 {} VIP{}插队, 新分数: {}", sessionId, vipLevel, newScore);
    }

    /**
     * 校验 session 必须处于 WAITING 状态, 否则拒绝操作。
     */
    private void assertSessionWaiting(long sessionId) {
        Session session = sessionMapper.selectById(sessionId);
        if (session == null || !"WAITING".equals(session.getStatus())) {
            throw new BizException("仅允许操作WAITING状态的会话, 当前状态: "
                    + (session != null ? session.getStatus() : "不存在"));
        }
    }

    private void audit(String operatorId, String operatorType, String action,
                       String targetType, String targetId, String detail) {
        AuditLog log = new AuditLog();
        log.setOperatorId(operatorId);
        log.setOperatorType(operatorType);
        log.setAction(action);
        log.setTargetType(targetType);
        log.setTargetId(targetId);
        log.setDetail(detail);
        auditLogMapper.insert(log);
    }
}
