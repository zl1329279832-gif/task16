package com.cs.alloc.service;

import com.cs.alloc.config.SlaRiskProperties;
import com.cs.alloc.domain.AuditLog;
import com.cs.alloc.domain.QueueEntry;
import com.cs.alloc.domain.Session;
import com.cs.alloc.mapper.AgentMapper;
import com.cs.alloc.mapper.AuditLogMapper;
import com.cs.alloc.mapper.QueueEntryMapper;
import com.cs.alloc.mapper.SessionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class SkillGroupDegradationService {
    private final QueueEntryMapper queueEntryMapper;
    private final AgentMapper agentMapper;
    private final AuditLogMapper auditLogMapper;
    private final RedisService redisService;
    private final MessageQueue messageQueue;
    private final SlaRiskProperties properties;
    private final SessionMapper sessionMapper;

    /**
     * 记录每个技能组开始无可用客服的时间戳。
     */
    private final Map<Long, Instant> noAgentSince = new ConcurrentHashMap<>();

    /**
     * 检查技能组是否需要降级: 无可用客服超过阈值则迁移到 fallback 技能组。
     * @return true if degradation was triggered
     */
    @Transactional
    public boolean checkAndDegrade(long skillGroupId) {
        Long fallbackId = properties.getFallbackSkillGroups().get(skillGroupId);
        if (fallbackId == null) {
            log.debug("技能组 {} 无配置的 fallback 技能组, 跳过降级", skillGroupId);
            return false;
        }

        int availableCount = countAvailableAgents(skillGroupId);
        if (availableCount > 0) {
            // Agents are available, reset timer
            noAgentSince.remove(skillGroupId);
            return false;
        }

        // No agents available
        Instant since = noAgentSince.computeIfAbsent(skillGroupId, k -> Instant.now());
        long elapsedSeconds = Duration.between(since, Instant.now()).getSeconds();

        if (elapsedSeconds < properties.getDegradationTimeoutSeconds()) {
            log.debug("技能组 {} 无可用客服 {}秒, 尚未达到降级阈值 {}秒",
                    skillGroupId, elapsedSeconds, properties.getDegradationTimeoutSeconds());
            return false;
        }

        // Trigger degradation — only migrate WAITING sessions
        List<QueueEntry> entries = queueEntryMapper.selectWaitingBySkillGroupId(skillGroupId);
        if (entries.isEmpty()) {
            log.debug("技能组 {} 无WAITING会话, 无需降级迁移", skillGroupId);
            return false;
        }

        int migrated = queueEntryMapper.batchUpdateSkillGroupWaiting(skillGroupId, fallbackId);
        for (QueueEntry entry : entries) {
            redisService.removeFromQueue(skillGroupId, entry.getSessionId());
            redisService.addToQueue(fallbackId, entry.getSessionId(),
                    entry.getPriorityScore() != null ? entry.getPriorityScore() : 0);
        }

        noAgentSince.remove(skillGroupId);

        // Publish degradation event
        messageQueue.publish(MessageQueue.Topics.SKILLGROUP_DEGRADED,
                String.format("{\"skillGroupId\":%d,\"fallbackId\":%d,\"count\":%d,\"timestamp\":%d}",
                        skillGroupId, fallbackId, migrated, System.currentTimeMillis()));

        audit("SYSTEM", "SYSTEM", "SKILL_GROUP_DEGRADE", "SKILL_GROUP",
                String.valueOf(skillGroupId),
                String.format("技能组%d降级到%d, 迁移%d个会话", skillGroupId, fallbackId, migrated));

        log.warn("技能组 {} 降级到 {}, 迁移 {} 个会话 (无客服持续 {}秒)",
                skillGroupId, fallbackId, migrated, elapsedSeconds);
        return true;
    }

    /**
     * 恢复降级: 将之前迁移的 WAITING 会话迁回原技能组。
     * 跳过已变为 ASSIGNED/ACTIVE 等非 WAITING 状态的会话。
     */
    @Transactional
    public boolean restore(long skillGroupId) {
        List<QueueEntry> migratedEntries = queueEntryMapper.selectByOriginalSkillGroupId(skillGroupId);
        if (migratedEntries.isEmpty()) {
            log.debug("技能组 {} 无已迁移的会话, 无需恢复", skillGroupId);
            return false;
        }

        // Find the skill group they were migrated to
        long currentSkillGroupId = migratedEntries.get(0).getSkillGroupId();
        int restored = 0;
        int skipped = 0;
        for (QueueEntry entry : migratedEntries) {
            // Only restore WAITING sessions
            Session session = sessionMapper.selectById(entry.getSessionId());
            if (session == null || !"WAITING".equals(session.getStatus())) {
                skipped++;
                continue;
            }
            queueEntryMapper.updateSkillGroupId(entry.getSessionId(), skillGroupId, null);
            redisService.removeFromQueue(currentSkillGroupId, entry.getSessionId());
            redisService.addToQueue(skillGroupId, entry.getSessionId(),
                    entry.getPriorityScore() != null ? entry.getPriorityScore() : 0);
            restored++;
        }

        messageQueue.publish(MessageQueue.Topics.SKILLGROUP_RESTORED,
                String.format("{\"skillGroupId\":%d,\"restoredCount\":%d,\"timestamp\":%d}",
                        skillGroupId, restored, System.currentTimeMillis()));

        audit("SYSTEM", "SYSTEM", "SKILL_GROUP_RESTORE", "SKILL_GROUP",
                String.valueOf(skillGroupId),
                String.format("技能组%d恢复, 迁回%d个WAITING会话, 跳过%d个非WAITING会话", skillGroupId, restored, skipped));

        log.info("技能组 {} 恢复, 迁回 {} 个WAITING会话, 跳过 {} 个非WAITING", skillGroupId, restored, skipped);
        return true;
    }

    private int countAvailableAgents(long skillGroupId) {
        var onlineAgents = agentMapper.selectBySkillGroupId(skillGroupId);
        int count = 0;
        for (var agent : onlineAgents) {
            if (redisService.isAgentAvailable(agent.getId()) && redisService.isHeartbeatAlive(agent.getId())) {
                count++;
            }
        }
        return count;
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
