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
     * 3. 超过阈值则重排队列
     * 4. 检查降级条件
     * 5. 定期保存快照
     * 6. 推送风险变化到 WebSocket
     */
    @Scheduled(fixedDelayString = "${cs.sla.interval-ms:5000}")
    public void runSlaCycle() {
        List<SkillGroup> groups = skillGroupMapper.selectAllActive();
        if (groups.isEmpty()) return;

        boolean hasCritical = false;

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

                // 3. Reorder if high risk
                if (highRisk) {
                    queueReorderService.reorderQueueByRisk(groupId);
                    hasCritical = true;
                }

                // 4. Save risk history
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
        if (hasCritical) {
            messageQueue.publish(MessageQueue.Topics.SLA_RISK_UPDATED,
                    String.format("{\"criticalAlert\":true,\"timestamp\":%d}", System.currentTimeMillis()));
        } else {
            messageQueue.publish(MessageQueue.Topics.SLA_RISK_UPDATED,
                    String.format("{\"criticalAlert\":false,\"timestamp\":%d}", System.currentTimeMillis()));
        }
    }
}
