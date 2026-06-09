package com.cs.alloc.service;

import com.cs.alloc.config.SlaRiskProperties;
import com.cs.alloc.domain.SkillGroup;
import com.cs.alloc.domain.SlaRiskHistory;
import com.cs.alloc.domain.SlaRiskScore;
import com.cs.alloc.mapper.SkillGroupMapper;
import com.cs.alloc.mapper.SlaRiskHistoryMapper;
import com.cs.alloc.ws.WsEventPusher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "cs.sla.enabled", havingValue = "true", matchIfMissing = true)
public class SlaRiskEngine {
    private final SkillGroupMapper skillGroupMapper;
    private final SlaRiskHistoryMapper slaRiskHistoryMapper;
    private final SlaRiskCalculator slaRiskCalculator;
    private final QueueReorderService queueReorderService;
    private final SkillGroupDegradationService degradationService;
    private final QueueSnapshotService snapshotService;
    private final MessageQueue messageQueue;
    private final WsEventPusher wsEventPusher;
    private final SlaRiskProperties properties;

    private long lastSnapshotTime = 0;

    /**
     * SLA 风险引擎主循环:
     * 1. 遍历所有活跃技能组
     * 2. 计算风险评分 (仅 WAITING 会话)
     * 3. 先落库 risk history (保证数据一致)
     * 4. 超过阈值则重排队列
     * 5. 检查降级条件
     * 6. 定期保存快照
     * 7. 最后统一推送风险更新事件 (保证 WS 推送时 history 已落库)
     */
    @Scheduled(fixedDelayString = "${cs.sla.interval-ms:5000}")
    public void runSlaCycle() {
        List<SkillGroup> groups = skillGroupMapper.selectAllActive();
        if (groups.isEmpty()) return;

        boolean hasCritical = false;
        // Collect risk summaries for WS push consistency
        List<Map<String, Object>> riskSummaries = new ArrayList<>();

        for (SkillGroup group : groups) {
            long groupId = group.getId();
            try {
                // 1. Calculate risk scores (only WAITING sessions)
                Map<Long, SlaRiskScore> riskScores = slaRiskCalculator.calculateSkillGroupRisks(groupId);
                if (riskScores.isEmpty()) continue;

                // 2. Check if any session exceeds threshold
                double maxRisk = 0;
                int sessionCount = 0;
                double totalRisk = 0;
                for (SlaRiskScore risk : riskScores.values()) {
                    if (risk.getRiskScore() == Double.MAX_VALUE) continue; // skip pinned
                    sessionCount++;
                    totalRisk += risk.getRiskScore();
                    if (risk.getRiskScore() > maxRisk) maxRisk = risk.getRiskScore();
                }
                boolean highRisk = maxRisk >= properties.getRiskThreshold();

                // 3. Save risk history FIRST (before events) to ensure DB consistency
                for (SlaRiskScore risk : riskScores.values()) {
                    if (risk.getRiskScore() == Double.MAX_VALUE) continue; // skip pinned
                    SlaRiskHistory history = new SlaRiskHistory();
                    history.setSessionId(risk.getSessionId());
                    history.setSkillGroupId(groupId);
                    history.setRiskScore(risk.getRiskScore());
                    history.setVipLevel(risk.getVipLevel());
                    history.setWaitSeconds(risk.getWaitSeconds());
                    history.setAvailableAgents(risk.getAvailableAgents());
                    history.setAvgAgentLoad(risk.getAvgAgentLoad());
                    history.setCalculatedAt(risk.getCalculatedAt());
                    slaRiskHistoryMapper.insert(history);
                }

                // 4. Reorder if high risk (after history is persisted)
                if (highRisk) {
                    queueReorderService.reorderQueueByRisk(groupId);
                    hasCritical = true;
                }

                // 5. Check degradation
                degradationService.checkAndDegrade(groupId);

                // Collect summary for unified WS push
                double avgRisk = sessionCount > 0 ? totalRisk / sessionCount : 0;
                riskSummaries.add(Map.of(
                        "skillGroupId", groupId,
                        "maxRisk", maxRisk,
                        "avgRisk", Math.round(avgRisk * 100.0) / 100.0,
                        "sessionCount", sessionCount
                ));

            } catch (Exception e) {
                log.error("SLA引擎处理技能组 {} 异常", groupId, e);
            }
        }

        // 6. Periodic snapshots
        long now = System.currentTimeMillis();
        if (now - lastSnapshotTime >= properties.getSnapshotIntervalSeconds() * 1000) {
            for (SkillGroup group : groups) {
                try {
                    snapshotService.saveSnapshot(group.getId(),
                            Duration.ofSeconds(properties.getSnapshotTtlSeconds()));
                } catch (Exception e) {
                    log.error("保存队列快照失败: skillGroupId={}", group.getId(), e);
                }
            }
            lastSnapshotTime = now;
        }

        // 7. Unified risk update push (after all DB writes complete)
        //    Includes risk summaries so WS clients can correlate with persisted history
        messageQueue.publish(MessageQueue.Topics.SLA_RISK_UPDATED,
                String.format("{\"criticalAlert\":%b,\"timestamp\":%d,\"riskSummaries\":%s}",
                        hasCritical, System.currentTimeMillis(), toJson(riskSummaries)));
    }

    private String toJson(List<Map<String, Object>> summaries) {
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.writeValueAsString(summaries);
        } catch (Exception e) {
            log.error("序列化风险摘要失败", e);
            return "[]";
        }
    }
}
