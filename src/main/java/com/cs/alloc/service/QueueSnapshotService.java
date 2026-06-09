package com.cs.alloc.service;

import com.cs.alloc.domain.QueueEntry;
import com.cs.alloc.domain.QueueSnapshot;
import com.cs.alloc.domain.Session;
import com.cs.alloc.domain.SlaRiskScore;
import com.cs.alloc.mapper.QueueEntryMapper;
import com.cs.alloc.mapper.SessionMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class QueueSnapshotService {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private final QueueEntryMapper queueEntryMapper;
    private final RedisService redisService;
    private final SlaRiskCalculator slaRiskCalculator;
    private final SessionMapper sessionMapper;

    /**
     * 保存指定技能组的队列快照到 Redis。
     * 仅包含 WAITING 状态的会话。
     */
    public QueueSnapshot saveSnapshot(long skillGroupId, Duration ttl) {
        List<QueueEntry> entries = queueEntryMapper.selectWaitingBySkillGroupId(skillGroupId);
        Map<Long, SlaRiskScore> riskScores = slaRiskCalculator.calculateSkillGroupRisks(skillGroupId);

        double avgRisk = riskScores.values().stream()
                .filter(r -> r.getRiskScore() != Double.MAX_VALUE)
                .mapToDouble(SlaRiskScore::getRiskScore)
                .average()
                .orElse(0.0);

        QueueSnapshot snapshot = QueueSnapshot.builder()
                .snapshotId(UUID.randomUUID().toString())
                .skillGroupId(skillGroupId)
                .entries(entries)
                .capturedAt(System.currentTimeMillis())
                .totalEntries(entries.size())
                .avgRiskScore(avgRisk)
                .build();

        try {
            String json = MAPPER.writeValueAsString(snapshot);
            redisService.saveQueueSnapshot(skillGroupId, json, ttl);
            log.debug("技能组 {} 队列快照已保存, {} 个条目, 平均风险: {}", skillGroupId, entries.size(), avgRisk);
        } catch (JsonProcessingException e) {
            log.error("序列化队列快照失败: skillGroupId={}", skillGroupId, e);
        }

        return snapshot;
    }

    /**
     * 获取指定技能组的最新队列快照。
     */
    public Optional<QueueSnapshot> getSnapshot(long skillGroupId) {
        return redisService.getQueueSnapshot(skillGroupId).map(json -> {
            try {
                return MAPPER.readValue(json, QueueSnapshot.class);
            } catch (JsonProcessingException e) {
                log.error("反序列化队列快照失败: skillGroupId={}", skillGroupId, e);
                return null;
            }
        });
    }

    /**
     * 从快照恢复 Redis sorted set (崩溃恢复场景)。
     * 仅恢复 WAITING 状态的会话，跳过已接入(ASSIGNED/ACTIVE)的会话。
     */
    public int recoverFromSnapshot(long skillGroupId) {
        Optional<QueueSnapshot> opt = getSnapshot(skillGroupId);
        if (opt.isEmpty()) {
            log.warn("技能组 {} 无可用快照, 无法恢复", skillGroupId);
            return 0;
        }

        QueueSnapshot snapshot = opt.get();
        int recovered = 0;
        int skipped = 0;
        for (QueueEntry entry : snapshot.getEntries()) {
            // Verify entry still exists in DB
            QueueEntry dbEntry = queueEntryMapper.selectBySessionId(entry.getSessionId());
            if (dbEntry == null) continue;

            // Only recover WAITING sessions
            Session session = sessionMapper.selectById(entry.getSessionId());
            if (session == null || !"WAITING".equals(session.getStatus())) {
                skipped++;
                log.debug("快照恢复跳过非WAITING会话: sessionId={}, status={}",
                        entry.getSessionId(), session != null ? session.getStatus() : "null");
                continue;
            }

            double score = dbEntry.getPriorityScore() != null ? dbEntry.getPriorityScore() : 0;
            redisService.addToQueue(skillGroupId, entry.getSessionId(), score);
            recovered++;
        }
        if (skipped > 0) {
            log.warn("从快照恢复技能组 {} 队列, 跳过 {} 个非WAITING会话", skillGroupId, skipped);
        }
        log.info("从快照恢复技能组 {} 队列, 恢复 {} 个WAITING条目", skillGroupId, recovered);
        return recovered;
    }
}
