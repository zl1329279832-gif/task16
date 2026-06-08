package com.customerservice.service;

import com.customerservice.mapper.AgentMapper;
import com.customerservice.mapper.AllocationLogMapper;
import com.customerservice.model.entity.Agent;
import com.customerservice.model.entity.AllocationLog;
import com.customerservice.model.entity.QueueEntry;
import com.customerservice.model.enums.AllocationAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Core allocation algorithm.
 *
 * Allocation strategy:
 * 1. Filter agents by skill group match
 * 2. Filter agents that are ONLINE and have remaining capacity
 * 3. Score agents by: remaining capacity (higher = better), proficiency (higher = better)
 * 4. Select the highest-scoring agent (least loaded + most skilled)
 *
 * For VIP customers, the algorithm increases search scope and may bump non-VIP sessions.
 */
@Service
public class AllocationService {
    private static final Logger log = LoggerFactory.getLogger(AllocationService.class);

    private final AgentMapper agentMapper;
    private final AllocationLogMapper allocationLogMapper;

    public AllocationService(AgentMapper agentMapper,
                             AllocationLogMapper allocationLogMapper) {
        this.agentMapper = agentMapper;
        this.allocationLogMapper = allocationLogMapper;
    }

    /**
     * Find the best agent for a queue entry.
     * Returns null if no suitable agent is available.
     */
    public Agent allocate(QueueEntry entry) {
        List<Agent> candidates;

        if (entry.getSkillGroupId() != null) {
            // Skill-group-specific allocation
            candidates = agentMapper.selectAvailableBySkillGroup(entry.getSkillGroupId());
        } else {
            // General queue - any online agent with capacity
            candidates = new ArrayList<>(agentMapper.selectAllOnline());
            candidates.removeIf(a -> !a.hasCapacity());
        }

        if (candidates.isEmpty()) {
            log.debug("No available agents for session [{}]", entry.getSessionId());
            return null;
        }

        // Score and sort: prefer agent with most remaining capacity (load balance)
        Agent bestAgent = candidates.stream()
                .max(Comparator.comparingInt(Agent::remainingCapacity))
                .orElse(null);

        if (bestAgent != null) {
            logAllocation(entry.getSessionId(), null, bestAgent.getId(),
                    AllocationAction.ASSIGN, "Auto-assigned by allocation algorithm");
        }

        return bestAgent;
    }

    /**
     * Find a suitable agent for transfer.
     * Excludes the current agent and optionally targets a specific agent.
     */
    public Agent allocateForTransfer(Long sessionId, Long fromAgentId,
                                     Long targetAgentId, Long skillGroupId) {
        if (targetAgentId != null) {
            // Targeted transfer
            Agent target = agentMapper.selectById(targetAgentId);
            if (target != null && target.hasCapacity()) {
                logAllocation(sessionId, fromAgentId, targetAgentId,
                        AllocationAction.TRANSFER, "Targeted transfer");
                return target;
            }
            log.warn("Target agent [{}] unavailable for transfer", targetAgentId);
            return null;
        }

        // Skill-group-based transfer
        List<Agent> candidates;
        if (skillGroupId != null) {
            candidates = agentMapper.selectAvailableBySkillGroup(skillGroupId);
        } else {
            candidates = new ArrayList<>(agentMapper.selectAllOnline());
            candidates.removeIf(a -> !a.hasCapacity());
        }

        // Exclude current agent
        candidates.removeIf(a -> a.getId().equals(fromAgentId));

        if (candidates.isEmpty()) return null;

        Agent bestAgent = candidates.stream()
                .max(Comparator.comparingInt(Agent::remainingCapacity))
                .orElse(null);

        if (bestAgent != null) {
            logAllocation(sessionId, fromAgentId, bestAgent.getId(),
                    AllocationAction.TRANSFER, "Transferred by skill group");
        }

        return bestAgent;
    }

    /**
     * Force takeover by a supervisor.
     */
    public void logTakeover(Long sessionId, Long fromAgentId, Long supervisorId) {
        logAllocation(sessionId, fromAgentId, supervisorId,
                AllocationAction.TAKEOVER, "Supervisor takeover");
    }

    public void logRebalance(Long sessionId, Long fromAgentId, Long toAgentId, String reason) {
        logAllocation(sessionId, fromAgentId, toAgentId, AllocationAction.REBALANCE, reason);
    }

    public List<AllocationLog> getSessionAllocationHistory(Long sessionId) {
        return allocationLogMapper.selectBySessionId(sessionId);
    }

    private void logAllocation(Long sessionId, Long fromAgentId, Long toAgentId,
                               AllocationAction action, String reason) {
        AllocationLog allocLog = new AllocationLog();
        allocLog.setSessionId(sessionId);
        allocLog.setFromAgentId(fromAgentId);
        allocLog.setToAgentId(toAgentId);
        allocLog.setAction(action);
        allocLog.setReason(reason);
        allocationLogMapper.insert(allocLog);

        log.info("Allocation: session={}, from={}, to={}, action={}, reason={}",
                sessionId, fromAgentId, toAgentId, action, reason);
    }
}
