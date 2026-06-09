package com.cs.alloc.service;

import com.cs.alloc.domain.Agent;
import com.cs.alloc.domain.QueueEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AllocationEngineTest {
    @Mock private QueueService queueService;
    @Mock private com.cs.alloc.mapper.AgentMapper agentMapper;
    @Mock private com.cs.alloc.mapper.AgentStateMapper agentStateMapper;
    @Mock private com.cs.alloc.mapper.SessionMapper sessionMapper;
    @Mock private com.cs.alloc.mapper.AllocationLogMapper allocationLogMapper;
    @Mock private RedisService redisService;
    @Mock private MessageQueue messageQueue;
    @Mock private DynamicRequeueService dynamicRequeueService;
    private AllocationEngine engine;

    @BeforeEach
    void setUp() {
        engine = new AllocationEngine(queueService, agentMapper, agentStateMapper, sessionMapper, allocationLogMapper, redisService, messageQueue, dynamicRequeueService);
        ReflectionTestUtils.setField(engine, "skillMatchBonus", 50);
        ReflectionTestUtils.setField(engine, "loadPenalty", 10);
    }

    @Test @DisplayName("空闲且技能匹配的客服得分最高")
    void idleSkillMatchAgentShouldScoreHighest() {
        Agent idleAgent = agent(1L, 1L, 5); Agent busyAgent = agent(2L, 1L, 5); Agent mismatch = agent(3L, 2L, 5);
        QueueEntry qe = qe(100L, 1L);
        int s1 = engine.calculateAgentScore(new AllocationEngine.AgentCandidate(idleAgent, 0), qe);
        int s2 = engine.calculateAgentScore(new AllocationEngine.AgentCandidate(busyAgent, 3), qe);
        int s3 = engine.calculateAgentScore(new AllocationEngine.AgentCandidate(mismatch, 0), qe);
        assertThat(s1).isGreaterThan(s2); assertThat(s1).isGreaterThan(s3);
        assertThat(s1).isEqualTo(80); assertThat(s2).isEqualTo(24); assertThat(s3).isEqualTo(30);
    }

    @Test @DisplayName("负载越低分数越高")
    void lowerLoadScoresHigher() {
        Agent a = agent(1L, 1L, 5); QueueEntry qe = qe(100L, 1L);
        assertThat(engine.calculateAgentScore(new AllocationEngine.AgentCandidate(a, 0), qe))
                .isGreaterThan(engine.calculateAgentScore(new AllocationEngine.AgentCandidate(a, 4), qe));
    }

    @Test @DisplayName("大容量客服得分更高")
    void largerCapacityScoresHigher() {
        Agent small = agent(1L, 1L, 3); Agent big = agent(2L, 1L, 10); QueueEntry qe = qe(100L, 1L);
        assertThat(engine.calculateAgentScore(new AllocationEngine.AgentCandidate(big, 2), qe))
                .isGreaterThan(engine.calculateAgentScore(new AllocationEngine.AgentCandidate(small, 2), qe));
    }

    @Test @DisplayName("选择评分最高的客服")
    void pickBestSelectsHighest() {
        QueueEntry qe = qe(100L, 1L);
        List<AllocationEngine.AgentCandidate> candidates = new ArrayList<>();
        candidates.add(new AllocationEngine.AgentCandidate(agent(1L, 1L, 5), 3));
        candidates.add(new AllocationEngine.AgentCandidate(agent(2L, 1L, 5), 0));
        candidates.add(new AllocationEngine.AgentCandidate(agent(3L, 1L, 5), 1));
        Optional<AllocationEngine.AgentCandidate> best = engine.pickBest(candidates, qe);
        assertThat(best).isPresent(); assertThat(best.get().agent.getId()).isEqualTo(2L);
    }

    @Test @DisplayName("VIP优先级高于普通")
    void vipHigherThanNormal() {
        LocalDateTime t = LocalDateTime.now().minusMinutes(5);
        assertThat(engine.calculatePriorityScore(2L, 3, t)).isGreaterThan(engine.calculatePriorityScore(1L, 0, t));
    }

    @Test @DisplayName("等待越久优先级越高")
    void longerWaitHigher() {
        assertThat(engine.calculatePriorityScore(1L, 0, LocalDateTime.now().minusMinutes(10)))
                .isGreaterThan(engine.calculatePriorityScore(1L, 0, LocalDateTime.now().minusMinutes(1)));
    }

    private Agent agent(Long id, Long sg, int cap) {
        Agent a = new Agent(); a.setId(id); a.setSkillGroupId(sg); a.setMaxCapacity(cap); a.setIsSupervisor(false); a.setStatus("ONLINE"); return a;
    }
    private QueueEntry qe(long sid, long sg) {
        QueueEntry q = new QueueEntry(); q.setSessionId(sid); q.setSkillGroupId(sg); q.setCustomerId(100L); q.setPriorityScore(0); q.setJoinedAt(LocalDateTime.now()); return q;
    }
}
