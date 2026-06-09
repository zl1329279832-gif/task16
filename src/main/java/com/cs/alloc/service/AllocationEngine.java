package com.cs.alloc.service;

import com.cs.alloc.domain.*;
import com.cs.alloc.mapper.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AllocationEngine {
    private final QueueService queueService;
    private final AgentMapper agentMapper;
    private final AgentStateMapper agentStateMapper;
    private final SessionMapper sessionMapper;
    private final AllocationLogMapper allocationLogMapper;
    private final RedisService redisService;
    private final MessageQueue messageQueue;

    @Value("${cs.allocation.skill-match-score:50}")
    private int skillMatchBonus;
    @Value("${cs.allocation.load-penalty:10}")
    private int loadPenalty;

    public AllocationEngine(QueueService queueService, AgentMapper agentMapper, AgentStateMapper agentStateMapper, SessionMapper sessionMapper, AllocationLogMapper allocationLogMapper, RedisService redisService, MessageQueue messageQueue) {
        this.queueService = queueService;
        this.agentMapper = agentMapper;
        this.agentStateMapper = agentStateMapper;
        this.sessionMapper = sessionMapper;
        this.allocationLogMapper = allocationLogMapper;
        this.redisService = redisService;
        this.messageQueue = messageQueue;
    }

    @Scheduled(fixedDelayString = "${cs.allocation.interval-ms:2000}")
    public void allocateRound() {
        List<QueueEntry> allWaiting = queueService.getAllWaiting();
        if (allWaiting.isEmpty()) return;

        Map<Long, List<QueueEntry>> byGroup = allWaiting.stream().collect(Collectors.groupingBy(QueueEntry::getSkillGroupId));

        for (Map.Entry<Long, List<QueueEntry>> entry : byGroup.entrySet()) {
            long skillGroupId = entry.getKey();
            List<QueueEntry> waiting = entry.getValue();
            List<AgentCandidate> candidates = loadCandidates(skillGroupId);
            if (candidates.isEmpty()) {
                log.debug("技能组 {} 无可用客服, 排队 {} 人", skillGroupId, waiting.size());
                continue;
            }
            for (QueueEntry qe : waiting) {
                if (candidates.isEmpty()) break;
                Optional<AgentCandidate> best = pickBest(candidates, qe);
                if (best.isPresent()) {
                    doAllocate(qe, best.get());
                    if (!redisService.hasCapacity(best.get().agent.getId())) {
                        candidates.remove(best.get());
                    }
                }
            }
        }
    }

    private List<AgentCandidate> loadCandidates(long skillGroupId) {
        List<Agent> onlineAgents = agentMapper.selectBySkillGroupId(skillGroupId);
        List<AgentCandidate> candidates = new ArrayList<>();
        for (Agent agent : onlineAgents) {
            if (!redisService.isAgentAvailable(agent.getId())) continue;
            if (!redisService.isHeartbeatAlive(agent.getId())) {
                log.warn("客服 {} 心跳过期, 跳过分配", agent.getId());
                continue;
            }
            if (!redisService.hasCapacity(agent.getId())) continue;
            AgentState state = agentStateMapper.selectByAgentId(agent.getId());
            int load = state != null ? state.getCurrentLoad() : 0;
            candidates.add(new AgentCandidate(agent, load));
        }
        return candidates;
    }

    Optional<AgentCandidate> pickBest(List<AgentCandidate> candidates, QueueEntry queueEntry) {
        AgentCandidate best = null;
        int bestScore = Integer.MIN_VALUE;
        for (AgentCandidate c : candidates) {
            int score = calculateAgentScore(c, queueEntry);
            if (score > bestScore) { best = c; bestScore = score; }
        }
        return Optional.ofNullable(best);
    }

    int calculateAgentScore(AgentCandidate candidate, QueueEntry queueEntry) {
        int score = 0;
        if (candidate.agent.getSkillGroupId().equals(queueEntry.getSkillGroupId())) score += skillMatchBonus;
        score -= candidate.currentLoad * loadPenalty;
        if (candidate.currentLoad == 0) score += 20;
        int remaining = candidate.agent.getMaxCapacity() - candidate.currentLoad;
        score += remaining * 2;
        return score;
    }

    public int calculatePriorityScore(long customerId, int vipLevel, LocalDateTime joinedAt) {
        long waitSeconds = ChronoUnit.SECONDS.between(joinedAt, LocalDateTime.now());
        return vipLevel * 10 + (int) waitSeconds;
    }

    @Transactional
    void doAllocate(QueueEntry qe, AgentCandidate candidate) {
        long sessionId = qe.getSessionId();
        long agentId = candidate.agent.getId();
        String lockKey = "session:" + sessionId;
        if (!redisService.tryLock(lockKey, java.time.Duration.ofSeconds(10))) {
            log.warn("分配锁失败, sessionId={}", sessionId);
            return;
        }
        try {
            Session session = sessionMapper.selectById(sessionId);
            if (session == null || !"WAITING".equals(session.getStatus())) {
                log.debug("会话状态不允许分配, sessionId={}, status={}", sessionId, session != null ? session.getStatus() : "null");
                return;
            }
            sessionMapper.assignAgent(sessionId, agentId, "ASSIGNED");
            redisService.incrementAgentLoad(agentId);
            redisService.bindSessionToAgent(sessionId, agentId);
            agentStateMapper.updateLoad(agentId, redisService.getAgentLoad(agentId));
            redisService.setCustomerSession(qe.getCustomerId(), sessionId);
            queueService.leave(sessionId);

            AllocationLog allocLog = new AllocationLog();
            allocLog.setSessionId(sessionId);
            allocLog.setAgentId(agentId);
            allocLog.setAction("ALLOCATE");
            allocLog.setReason(String.format("技能组%d自动分配", qe.getSkillGroupId()));
            allocLog.setScoreDetail(String.format("{\"agentLoad\":%d,\"agentCapacity\":%d}", candidate.currentLoad, candidate.agent.getMaxCapacity()));
            allocationLogMapper.insert(allocLog);

            messageQueue.publish(MessageQueue.Topics.SESSION_ALLOCATED, String.format("{\"sessionId\":%d,\"agentId\":%d,\"customerId\":%d,\"sessionNo\":\"%s\"}", sessionId, agentId, qe.getCustomerId(), session.getSessionNo()));
            log.info("分配成功: session={} -> agent={}", sessionId, agentId);
        } finally {
            redisService.unlock(lockKey);
        }
    }

    @Scheduled(fixedDelay = 30000)
    public void rebalanceOverloaded() {
        List<Agent> onlineAgents = agentMapper.selectAllOnline();
        for (Agent agent : onlineAgents) {
            int load = redisService.getAgentLoad(agent.getId());
            int capacity = agent.getMaxCapacity();
            if (load > capacity) {
                log.warn("客服 {} 超载: load={}, capacity={}", agent.getId(), load, capacity);
                reassignExcessSessions(agent, load - capacity);
            }
        }
    }

    @Transactional
    void reassignExcessSessions(Agent agent, int excessCount) {
        List<Session> activeSessions = sessionMapper.selectByAgentIdAndStatus(agent.getId(), "ACTIVE");
        int reassigned = 0;
        for (int i = activeSessions.size() - 1; i >= 0 && reassigned < excessCount; i--) {
            Session s = activeSessions.get(i);
            sessionMapper.updateStatus(s.getId(), "WAITING");
            redisService.unbindSessionFromAgent(s.getId(), agent.getId());
            redisService.decrementAgentLoad(agent.getId());
            queueService.rejoin(s);
            reassigned++;
            AllocationLog allocLog = new AllocationLog();
            allocLog.setSessionId(s.getId());
            allocLog.setAgentId(agent.getId());
            allocLog.setAction("REASSIGN");
            allocLog.setReason("客服超载退回排队");
            allocationLogMapper.insert(allocLog);
        }
        agentStateMapper.updateLoad(agent.getId(), redisService.getAgentLoad(agent.getId()));
    }

    static class AgentCandidate {
        final Agent agent;
        final int currentLoad;
        AgentCandidate(Agent agent, int currentLoad) { this.agent = agent; this.currentLoad = currentLoad; }
    }
}
