package com.cs.alloc.service;

import com.cs.alloc.config.SlaRiskProperties;
import com.cs.alloc.domain.*;
import com.cs.alloc.mapper.AgentMapper;
import com.cs.alloc.mapper.CustomerMapper;
import com.cs.alloc.mapper.QueueEntryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class SlaRiskCalculator {
    private final QueueEntryMapper queueEntryMapper;
    private final CustomerMapper customerMapper;
    private final AgentMapper agentMapper;
    private final RedisService redisService;
    private final SlaRiskProperties properties;

    /**
     * 计算单个排队条目的 SLA 风险评分。
     * 公式: min(100, vipWeight*level + waitWeight*seconds + availabilityWeight*(maxAvail-avail)/maxAvail*maxAgents
     *        + loadWeight*avgLoad + ahtMultiplier*aht + heartbeatPenalty)
     */
    public SlaRiskScore calculateRisk(QueueEntry entry) {
        long now = System.currentTimeMillis();
        LocalDateTime joinedAt = entry.getJoinedAt() != null ? entry.getJoinedAt() : LocalDateTime.now();
        long waitSeconds = ChronoUnit.SECONDS.between(joinedAt, LocalDateTime.now());

        Customer customer = customerMapper.selectById(entry.getCustomerId());
        int vipLevel = customer != null ? customer.getVipLevel() : 0;

        long skillGroupId = entry.getSkillGroupId();
        List<Agent> onlineAgents = agentMapper.selectBySkillGroupId(skillGroupId);
        int availableAgents = 0;
        double totalLoad = 0;
        int totalCapacity = 0;
        boolean heartbeatOk = true;

        for (Agent agent : onlineAgents) {
            if (redisService.isAgentAvailable(agent.getId())) {
                availableAgents++;
                totalLoad += redisService.getAgentLoad(agent.getId());
                totalCapacity += agent.getMaxCapacity();
                if (!redisService.isHeartbeatAlive(agent.getId())) {
                    heartbeatOk = false;
                }
            }
        }

        double avgLoad = availableAgents > 0 ? (totalLoad / availableAgents) : 0;
        long historicalAht = 300L; // default AHT

        double riskScore = 0;
        // VIP component: higher VIP = higher risk if waiting
        riskScore += vipLevel * properties.getVipWeight();
        // Wait component
        riskScore += waitSeconds * properties.getWaitWeight();
        // Availability component: fewer agents = higher risk
        if (availableAgents == 0) {
            riskScore += properties.getAvailabilityWeight() * 4; // max penalty when no agents
        } else {
            double loadRatio = totalCapacity > 0 ? totalLoad / totalCapacity : 0;
            riskScore += loadRatio * properties.getAvailabilityWeight();
        }
        // Load component
        riskScore += avgLoad * properties.getLoadWeight();
        // AHT component
        riskScore += historicalAht * properties.getAhtMultiplier();
        // Heartbeat penalty
        if (!heartbeatOk || availableAgents == 0) {
            riskScore += properties.getHeartbeatPenalty();
        }

        // Cap at 100
        riskScore = Math.min(100.0, riskScore);

        // Pinned sessions get maximum priority
        boolean isPinned = entry.getPinned() != null && entry.getPinned();
        if (isPinned) {
            riskScore = Double.MAX_VALUE;
        }

        return SlaRiskScore.builder()
                .sessionId(entry.getSessionId())
                .customerId(entry.getCustomerId())
                .skillGroupId(skillGroupId)
                .vipLevel(vipLevel)
                .waitSeconds(waitSeconds)
                .riskScore(riskScore)
                .availableAgents(availableAgents)
                .avgAgentLoad(avgLoad)
                .historicalAht(historicalAht)
                .agentHeartbeatOk(heartbeatOk)
                .calculatedAt(now)
                .pinned(isPinned)
                .vipJumpApplied(false)
                .degraded(entry.getOriginalSkillGroupId() != null)
                .build();
    }

    /**
     * 批量计算技能组内所有 WAITING 状态排队条目的风险评分。
     * 只处理 WAITING 会话，防止 ASSIGNED/ACTIVE 会话被误纳入重排。
     */
    public Map<Long, SlaRiskScore> calculateSkillGroupRisks(long skillGroupId) {
        List<QueueEntry> entries = queueEntryMapper.selectWaitingBySkillGroupId(skillGroupId);
        Map<Long, SlaRiskScore> result = new LinkedHashMap<>();
        for (QueueEntry entry : entries) {
            SlaRiskScore score = calculateRisk(entry);
            result.put(entry.getSessionId(), score);
        }
        return result;
    }
}
