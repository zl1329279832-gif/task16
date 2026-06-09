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
     * 2. 计算风险评分
     * 3. 保存风险历史到 DB (先于 WS 推送, 保证 DB 有记录)
     * 4. 超过阈值则重排队列
     * 5. 检查降级条件
     * 6. 定期保存快照
     * 7. 推送风险变化到 WebSocket (携带与 DB 一致的逐会话风险数据)
     *
     * 事件顺序保证: DB insert → MQ publish → WS push, 避免推送的风险数据与
     * SlaRiskHistory 不一致。
     */
    @Scheduled(fixedDelayString = "${cs.sla.interval-ms:5000}")
    public void runSlaCycle() {
        List<SkillGroup> groups = skillGroupMapper.selectAllActive();
        if (groups.isEmpty()) return;

        boolean hasCritical = false;
        // Collect per-session risk data for consistent WS push
        List<String> sessionRiskDetails = new ArrayList<>();

        for (SkillGroup group : groups) {
            long groupId = group.getId();
            try {
                // 1. Calculate risk scores
                Map<Long, SlaRiskScore> riskScores = slaRiskCalculator.calculateSkillGroupRisks(groupId);
                if (riskScores.isEmpty()) continue;

                // 2. Check if any session exceeds threshold
                boolean highRisk = riskScores.values().stream()
                        .anyMatch(r -> r.getRiskScore() != Double.MAX_VALUE
                                && r.getRiskScore() >= properties.getRiskThreshold());

                // 3. Save risk history FIRST (before MQ publish, so DB has the data)
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

                    // Collect per-session risk detail for WS push
                    sessionRiskDetails.add(String.format(
                            "{\"sessionId\":%d,\"skillGroupId\":%d,\"riskScore\":%.1f,\"vipLevel\":%d}",
                            risk.getSessionId(), groupId, risk.getRiskScore(), risk.getVipLevel()));
                }

                // 4. Reorder if high risk (after history is persisted)
                if (highRisk) {
                    queueReorderService.reorderQueueByRisk(groupId);
                    hasCritical = true;
                }

                // 5. Check degradation
                degradationService.checkAndDegrade(groupId);

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

        // 7. Push risk updates via MQ -> WebSocket
        // Include per-session risk data so WS push is consistent with SlaRiskHistory
        String riskDetailsJson = String.join(",", sessionRiskDetails);
        if (hasCritical) {
            messageQueue.publish(MessageQueue.Topics.SLA_RISK_UPDATED,
                    String.format("{\"criticalAlert\":true,\"sessionRisks\":[%s],\"timestamp\":%d}",
                            riskDetailsJson, System.currentTimeMillis()));
        } else {
            messageQueue.publish(MessageQueue.Topics.SLA_RISK_UPDATED,
                    String.format("{\"criticalAlert\":false,\"sessionRisks\":[%s],\"timestamp\":%d}",
                            riskDetailsJson, System.currentTimeMillis()));
        }
    }
}
