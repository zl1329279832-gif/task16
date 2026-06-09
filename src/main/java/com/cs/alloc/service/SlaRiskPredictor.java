package com.cs.alloc.service;

import com.cs.alloc.domain.*;
import com.cs.alloc.mapper.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class SlaRiskPredictor {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final QueueEntryMapper queueEntryMapper;
    private final AgentMapper agentMapper;
    private final CustomerMapper customerMapper;
    private final RedisService redisService;
    private final MessageQueue messageQueue;

    @Value("${cs.sla.default-timeout-seconds:1800}")
    private int defaultTimeoutSeconds;
    @Value("${cs.sla.vip-timeout-multiplier:0.5}")
    private double vipTimeoutMultiplier;
    @Value("${cs.sla.medium-threshold:30}")
    private int mediumThreshold;
    @Value("${cs.sla.high-threshold:60}")
    private int highThreshold;
    @Value("${cs.sla.critical-threshold:85}")
    private int criticalThreshold;
    @Value("${cs.sla.avg-handle-seconds:300}")
    private int avgHandleSeconds;

    public SlaRiskPredictor(QueueEntryMapper queueEntryMapper, AgentMapper agentMapper,
                            CustomerMapper customerMapper, RedisService redisService,
                            MessageQueue messageQueue) {
        this.queueEntryMapper = queueEntryMapper;
        this.agentMapper = agentMapper;
        this.customerMapper = customerMapper;
        this.redisService = redisService;
        this.messageQueue = messageQueue;
    }

    /**
     * 定时扫描排队会话, 计算 SLA 风险分数并推送变化。
     */
    @Scheduled(fixedDelayString = "${cs.sla.risk-scan-interval-ms:5000}")
    public void scanRisk() {
        List<QueueEntry> allWaiting = queueEntryMapper.selectAll();
        if (allWaiting.isEmpty()) return;

        Map<Long, List<QueueEntry>> byGroup = allWaiting.stream()
                .collect(Collectors.groupingBy(QueueEntry::getSkillGroupId));

        for (Map.Entry<Long, List<QueueEntry>> entry : byGroup.entrySet()) {
            long skillGroupId = entry.getKey();
            List<QueueEntry> entries = entry.getValue();
            int availableAgents = countAvailableAgents(skillGroupId);
            int totalLoad = calculateTotalLoad(skillGroupId);

            for (QueueEntry qe : entries) {
                String oldRiskLevel = qe.getRiskLevel() != null ? qe.getRiskLevel() : "LOW";

                // 初始化 SLA 截止时间
                if (qe.getSlaDeadline() == null) {
                    LocalDateTime deadline = calculateSlaDeadline(qe);
                    qe.setSlaDeadline(deadline);
                    queueEntryMapper.updateSlaDeadline(qe.getSessionId(), deadline);
                }

                int riskScore = calculateRiskScore(qe, availableAgents, totalLoad);
                SlaRiskLevel newLevel = SlaRiskLevel.fromScore(riskScore, mediumThreshold, highThreshold, criticalThreshold);

                qe.setRiskScore(riskScore);
                qe.setRiskLevel(newLevel.name());
                queueEntryMapper.updateRisk(qe.getSessionId(), riskScore, newLevel.name());
                redisService.saveSlaRisk(qe.getSessionId(), riskScore, newLevel.name(),
                        qe.getSlaDeadline().toString());

                // 风险等级变化时推送
                if (!oldRiskLevel.equals(newLevel.name())) {
                    publishRiskChange(qe, oldRiskLevel, newLevel.name());
                }
            }
        }
    }

    /**
     * 定时保存队列快照到 Redis。
     */
    @Scheduled(fixedDelayString = "${cs.sla.snapshot-interval-ms:10000}")
    public void saveSnapshots() {
        List<QueueEntry> all = queueEntryMapper.selectAll();
        Map<Long, List<QueueEntry>> byGroup = all.stream()
                .collect(Collectors.groupingBy(QueueEntry::getSkillGroupId));

        for (Map.Entry<Long, List<QueueEntry>> entry : byGroup.entrySet()) {
            try {
                List<Map<String, Object>> snapshot = entry.getValue().stream()
                        .map(this::toSnapshotMap).collect(Collectors.toList());
                String json = MAPPER.writeValueAsString(snapshot);
                redisService.saveQueueSnapshot(entry.getKey(), json);
            } catch (Exception e) {
                log.error("保存队列快照失败, skillGroupId={}", entry.getKey(), e);
            }
        }
    }

    /**
     * 计算单个会话的 SLA 风险分数 (0-100)。
     *
     * 算法:
     * 1. 时间维度 (0-60分): 已等待时间 / SLA超时时间 * 60
     * 2. 资源维度 (0-25分): 无可用客服+25, 负载高+15
     * 3. 心跳维度 (0-15分): 技能组内心跳异常客服比例 * 15
     */
    public int calculateRiskScore(QueueEntry qe, int availableAgents, int totalLoad) {
        double score = 0;

        // 时间维度: 已等待占 SLA 比例
        long waitSeconds = ChronoUnit.SECONDS.between(qe.getJoinedAt(), LocalDateTime.now());
        long slaSeconds = qe.getSlaDeadline() != null
                ? ChronoUnit.SECONDS.between(qe.getJoinedAt(), qe.getSlaDeadline())
                : defaultTimeoutSeconds;
        if (slaSeconds <= 0) slaSeconds = 1;
        double timeRatio = (double) waitSeconds / slaSeconds;
        score += Math.min(timeRatio * 60, 60);

        // 超时后分数加速
        if (waitSeconds > slaSeconds) {
            score += Math.min((waitSeconds - slaSeconds) / 60.0 * 5, 15);
        }

        // 资源维度: 可用客服数
        if (availableAgents == 0) {
            score += 25;
        } else {
            // 每个等待者对应的客服数越少风险越高
            List<QueueEntry> groupEntries = queueEntryMapper.selectBySkillGroupId(qe.getSkillGroupId());
            int queueSize = groupEntries.size();
            double ratio = queueSize > 0 ? (double) availableAgents / queueSize : 1.0;
            if (ratio < 0.5) score += 15;
            else if (ratio < 1.0) score += 8;
        }

        // 心跳维度
        List<Agent> agents = agentMapper.selectBySkillGroupId(qe.getSkillGroupId());
        long deadHeartbeats = agents.stream()
                .filter(a -> !redisService.isHeartbeatAlive(a.getId()))
                .count();
        if (!agents.isEmpty()) {
            score += (double) deadHeartbeats / agents.size() * 15;
        }

        return Math.min((int) Math.round(score), 100);
    }

    /**
     * 根据 VIP 等级计算 SLA 截止时间。
     * VIP 等级越高, SLA 窗口越短。
     */
    public LocalDateTime calculateSlaDeadline(QueueEntry qe) {
        Customer customer = customerMapper.selectById(qe.getCustomerId());
        int vipLevel = customer != null ? customer.getVipLevel() : 0;
        int timeoutSeconds = getSlaTimeoutByVip(vipLevel);
        return qe.getJoinedAt().plusSeconds(timeoutSeconds);
    }

    public int getSlaTimeoutByVip(int vipLevel) {
        if (vipLevel <= 0) return defaultTimeoutSeconds;
        // VIP 等级越高, 超时越短: level1=50%, level2=35%, level3=25%
        double multiplier = Math.max(1.0 - vipLevel * vipTimeoutMultiplier * 0.5, 0.25);
        return (int) (defaultTimeoutSeconds * multiplier);
    }

    int countAvailableAgents(long skillGroupId) {
        List<Agent> agents = agentMapper.selectBySkillGroupId(skillGroupId);
        return (int) agents.stream()
                .filter(a -> redisService.isAgentAvailable(a.getId()))
                .filter(a -> redisService.hasCapacity(a.getId()))
                .filter(a -> redisService.isHeartbeatAlive(a.getId()))
                .count();
    }

    int calculateTotalLoad(long skillGroupId) {
        List<Agent> agents = agentMapper.selectBySkillGroupId(skillGroupId);
        return agents.stream()
                .filter(a -> redisService.isAgentAvailable(a.getId()))
                .mapToInt(a -> redisService.getAgentLoad(a.getId()))
                .sum();
    }

    private void publishRiskChange(QueueEntry qe, String oldLevel, String newLevel) {
        try {
            String msg = MAPPER.writeValueAsString(Map.of(
                    "sessionId", qe.getSessionId(),
                    "customerId", qe.getCustomerId(),
                    "skillGroupId", qe.getSkillGroupId(),
                    "riskScore", qe.getRiskScore(),
                    "oldRiskLevel", oldLevel,
                    "newRiskLevel", newLevel,
                    "slaDeadline", qe.getSlaDeadline() != null ? qe.getSlaDeadline().toString() : ""
            ));
            messageQueue.publish(MessageQueue.Topics.SLA_RISK_CHANGED, msg);
            log.info("SLA风险变化: session={}, {} -> {}, score={}", qe.getSessionId(), oldLevel, newLevel, qe.getRiskScore());
        } catch (Exception e) {
            log.error("发布SLA风险变化事件失败", e);
        }
    }

    private Map<String, Object> toSnapshotMap(QueueEntry qe) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("sessionId", qe.getSessionId());
        map.put("customerId", qe.getCustomerId());
        map.put("skillGroupId", qe.getSkillGroupId());
        map.put("priorityScore", qe.getPriorityScore());
        map.put("position", qe.getPosition());
        map.put("riskScore", qe.getRiskScore());
        map.put("riskLevel", qe.getRiskLevel());
        map.put("pinned", Boolean.TRUE.equals(qe.getPinned()));
        map.put("joinedAt", qe.getJoinedAt() != null ? qe.getJoinedAt().toString() : null);
        map.put("slaDeadline", qe.getSlaDeadline() != null ? qe.getSlaDeadline().toString() : null);
        return map;
    }
}
