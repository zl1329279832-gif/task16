package com.cs.alloc.service;

import com.cs.alloc.common.BizException;
import com.cs.alloc.domain.AuditLog;
import com.cs.alloc.domain.QueueEntry;
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
     * 在重排前逐条校验 session 状态，跳过 ASSIGNED/ACTIVE/TRANSFERRING/SUSPENDED/CLOSED。
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
            Map<Long, SlaRiskScore> allRiskScores = slaRiskCalculator.calculateSkillGroupRisks(skillGroupId);
            if (allRiskScores.isEmpty()) return false;

            // Create mutable copy for filtering
            Map<Long, SlaRiskScore> riskScores = new LinkedHashMap<>(allRiskScores);

            // Filter: only reorder sessions that are still WAITING
            List<Long> skippedSessionIds = new ArrayList<>();
            riskScores.entrySet().removeIf(e -> {
                String status = sessionMapper.selectStatus(e.getKey());
                if (!"WAITING".equals(status)) {
                    skippedSessionIds.add(e.getKey());
                    return true;
                }
                return false;
            });

            if (!skippedSessionIds.isEmpty()) {
                log.info("技能组 {} 重排跳过 {} 个非WAITING会话: {}",
                        skillGroupId, skippedSessionIds.size(), skippedSessionIds);
                // Audit: record skipped sessions for traceability
                audit("SYSTEM", "SYSTEM", "REORDER_SKIP_NON_WAITING", "SKILL_GROUP",
                        String.valueOf(skillGroupId),
                        String.format("跳过%d个非WAITING会话: %s", skippedSessionIds.size(), skippedSessionIds));
            }

            if (riskScores.isEmpty()) return false;

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
                    String.format("{\"skillGroupId\":%d,\"count\":%d,\"skipped\":%d,\"timestamp\":%d}",
                            skillGroupId, sorted.size(), skippedSessionIds.size(), System.currentTimeMillis()));

            // Audit log
            audit("SYSTEM", "SYSTEM", "QUEUE_REORDER", "SKILL_GROUP",
                    String.valueOf(skillGroupId),
                    String.format("按风险评分重排队列, 共%d个会话, 跳过%d个非WAITING",
                            sorted.size(), skippedSessionIds.size()));

            log.info("技能组 {} 队列重排完成, {} 个会话, 跳过 {} 个", skillGroupId, sorted.size(), skippedSessionIds.size());
            return true;
        } finally {
            redisService.unlock(lockKey, lockOwner);
        }
    }

    /**
     * 人工置顶: 将指定会话固定在队列最前面。
     * 仅允许 WAITING 状态的会话置顶。
     */
    @Transactional
    public void pinSession(long sessionId, String operatorId) {
        QueueEntry entry = queueEntryMapper.selectBySessionId(sessionId);
        if (entry == null) throw new BizException("会话不在排队中");

        // Validate session is still WAITING before pinning
        String status = sessionMapper.selectStatus(sessionId);
        if (!"WAITING".equals(status)) {
            throw new BizException("会话状态为" + status + ", 仅WAITING状态可置顶");
        }

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
     * 仅允许 WAITING 状态的会话取消置顶。
     */
    @Transactional
    public void unpinSession(long sessionId, String operatorId) {
        QueueEntry entry = queueEntryMapper.selectBySessionId(sessionId);
        if (entry == null) throw new BizException("会话不在排队中");

        // Validate session is still WAITING
        String status = sessionMapper.selectStatus(sessionId);
        if (!"WAITING".equals(status)) {
            throw new BizException("会话状态为" + status + ", 仅WAITING状态可取消置顶");
        }

        queueEntryMapper.updatePinned(sessionId, false);
        redisService.clearPinnedFlag(sessionId);

        audit(operatorId, "SUPERVISOR", "SESSION_UNPIN", "SESSION",
                String.valueOf(sessionId), "取消置顶");
        log.info("会话 {} 已取消置顶, 操作人: {}", sessionId, operatorId);
    }

    /**
     * VIP 插队: 为 VIP 客户一次性增加优先级分数。
     * 仅允许 WAITING 状态的会话执行 VIP 插队。
     */
    @Transactional
    public void applyVipJump(long sessionId, int vipLevel, String operatorId) {
        QueueEntry entry = queueEntryMapper.selectBySessionId(sessionId);
        if (entry == null) throw new BizException("会话不在排队中");

        // Validate session is still WAITING
        String status = sessionMapper.selectStatus(sessionId);
        if (!"WAITING".equals(status)) {
            throw new BizException("会话状态为" + status + ", 仅WAITING状态可VIP插队");
        }

        int bonus = vipLevel * 10;
        int newScore = (entry.getPriorityScore() != null ? entry.getPriorityScore() : 0) + bonus;
        queueEntryMapper.updatePriorityScore(sessionId, newScore);
        redisService.updateQueueEntryScore(entry.getSkillGroupId(), sessionId, newScore);

        audit(operatorId, "SYSTEM", "VIP_JUMP", "SESSION",
                String.valueOf(sessionId),
                String.format("VIP%d插队, 加分%d, 新分数%d", vipLevel, bonus, newScore));
        log.info("会话 {} VIP{}插队, 新分数: {}", sessionId, vipLevel, newScore);
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
