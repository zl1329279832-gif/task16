package com.cs.alloc.service;

import com.cs.alloc.domain.*;
import com.cs.alloc.mapper.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SlaRiskPredictorTest {
    @Mock private QueueEntryMapper queueEntryMapper;
    @Mock private AgentMapper agentMapper;
    @Mock private CustomerMapper customerMapper;
    @Mock private RedisService redisService;
    @Mock private MessageQueue messageQueue;
    private SlaRiskPredictor predictor;

    @BeforeEach
    void setUp() {
        predictor = new SlaRiskPredictor(queueEntryMapper, agentMapper, customerMapper, redisService, messageQueue);
        ReflectionTestUtils.setField(predictor, "defaultTimeoutSeconds", 1800);
        ReflectionTestUtils.setField(predictor, "vipTimeoutMultiplier", 0.5);
        ReflectionTestUtils.setField(predictor, "mediumThreshold", 30);
        ReflectionTestUtils.setField(predictor, "highThreshold", 60);
        ReflectionTestUtils.setField(predictor, "criticalThreshold", 85);
        ReflectionTestUtils.setField(predictor, "avgHandleSeconds", 300);
    }

    // ==================== 风险分数计算 ====================

    @Test @DisplayName("刚入队的会话风险分数低")
    void newEntryHasLowRisk() {
        QueueEntry qe = qe(1L, 1L, LocalDateTime.now());
        qe.setSlaDeadline(LocalDateTime.now().plusMinutes(30));
        Agent agent = agent(1L, 1L);
        when(agentMapper.selectBySkillGroupId(1L)).thenReturn(List.of(agent));
        when(redisService.isHeartbeatAlive(1L)).thenReturn(true);
        when(queueEntryMapper.selectBySkillGroupId(1L)).thenReturn(List.of(qe));

        int score = predictor.calculateRiskScore(qe, 1, 0);
        assertThat(score).isLessThan(30);
    }

    @Test @DisplayName("等待超过一半SLA时间风险升至MEDIUM")
    void halfWaitTimeShouldBeMediumRisk() {
        // 入队 15 分钟, SLA 30 分钟 -> 时间维度 50% * 60 = 30分
        QueueEntry qe = qe(1L, 1L, LocalDateTime.now().minusMinutes(15));
        qe.setSlaDeadline(qe.getJoinedAt().plusMinutes(30));
        Agent agent = agent(1L, 1L);
        when(agentMapper.selectBySkillGroupId(1L)).thenReturn(List.of(agent));
        when(redisService.isHeartbeatAlive(1L)).thenReturn(true);
        when(queueEntryMapper.selectBySkillGroupId(1L)).thenReturn(List.of(qe));

        int score = predictor.calculateRiskScore(qe, 1, 0);
        SlaRiskLevel level = SlaRiskLevel.fromScore(score, 30, 60, 85);
        assertThat(score).isGreaterThanOrEqualTo(30);
        assertThat(level).isIn(SlaRiskLevel.MEDIUM, SlaRiskLevel.HIGH);
    }

    @Test @DisplayName("无可用客服风险大幅增加")
    void noAgentAvailableShouldIncreaseRisk() {
        QueueEntry qe = qe(1L, 1L, LocalDateTime.now().minusMinutes(5));
        qe.setSlaDeadline(qe.getJoinedAt().plusMinutes(30));
        when(agentMapper.selectBySkillGroupId(1L)).thenReturn(Collections.emptyList());
        when(queueEntryMapper.selectBySkillGroupId(1L)).thenReturn(List.of(qe));

        int scoreNoAgent = predictor.calculateRiskScore(qe, 0, 0);

        // 对比有客服的情况
        Agent agent = agent(1L, 1L);
        when(agentMapper.selectBySkillGroupId(1L)).thenReturn(List.of(agent));
        when(redisService.isHeartbeatAlive(1L)).thenReturn(true);
        int scoreWithAgent = predictor.calculateRiskScore(qe, 1, 0);

        assertThat(scoreNoAgent).isGreaterThan(scoreWithAgent);
        assertThat(scoreNoAgent - scoreWithAgent).isGreaterThanOrEqualTo(15);
    }

    @Test @DisplayName("心跳异常增加风险分数")
    void deadHeartbeatIncreasesRisk() {
        QueueEntry qe = qe(1L, 1L, LocalDateTime.now().minusMinutes(5));
        qe.setSlaDeadline(qe.getJoinedAt().plusMinutes(30));
        Agent agent1 = agent(1L, 1L);
        Agent agent2 = agent(2L, 1L);

        // 所有心跳正常
        when(agentMapper.selectBySkillGroupId(1L)).thenReturn(Arrays.asList(agent1, agent2));
        when(redisService.isHeartbeatAlive(1L)).thenReturn(true);
        when(redisService.isHeartbeatAlive(2L)).thenReturn(true);
        when(queueEntryMapper.selectBySkillGroupId(1L)).thenReturn(List.of(qe));
        int scoreAllAlive = predictor.calculateRiskScore(qe, 2, 0);

        // 一个心跳异常
        when(redisService.isHeartbeatAlive(2L)).thenReturn(false);
        int scoreOneDead = predictor.calculateRiskScore(qe, 1, 0);

        assertThat(scoreOneDead).isGreaterThan(scoreAllAlive);
    }

    @Test @DisplayName("SLA超时后风险分数达到CRITICAL")
    void slaTimeoutShouldBeCritical() {
        // 入队 35 分钟, SLA 30 分钟 -> 已超时
        QueueEntry qe = qe(1L, 1L, LocalDateTime.now().minusMinutes(35));
        qe.setSlaDeadline(qe.getJoinedAt().plusMinutes(30));
        when(agentMapper.selectBySkillGroupId(1L)).thenReturn(Collections.emptyList());

        int score = predictor.calculateRiskScore(qe, 0, 0);
        SlaRiskLevel level = SlaRiskLevel.fromScore(score, 30, 60, 85);
        assertThat(score).isGreaterThanOrEqualTo(85);
        assertThat(level).isEqualTo(SlaRiskLevel.CRITICAL);
    }

    // ==================== SLA 截止时间 ====================

    @Test @DisplayName("普通客户SLA超时为默认值")
    void normalCustomerDefaultTimeout() {
        assertThat(predictor.getSlaTimeoutByVip(0)).isEqualTo(1800);
    }

    @Test @DisplayName("VIP等级越高SLA超时越短")
    void higherVipShorterTimeout() {
        int timeout1 = predictor.getSlaTimeoutByVip(1);
        int timeout2 = predictor.getSlaTimeoutByVip(2);
        int timeout3 = predictor.getSlaTimeoutByVip(3);
        assertThat(timeout1).isLessThan(1800);
        assertThat(timeout2).isLessThan(timeout1);
        assertThat(timeout3).isLessThan(timeout2);
    }

    @Test @DisplayName("SLA截止时间考虑VIP等级")
    void slaDeadlineConsidersVipLevel() {
        QueueEntry qe = qe(1L, 1L, LocalDateTime.now());
        Customer vipCustomer = new Customer();
        vipCustomer.setId(100L);
        vipCustomer.setVipLevel(3);
        when(customerMapper.selectById(100L)).thenReturn(vipCustomer);

        LocalDateTime deadline = predictor.calculateSlaDeadline(qe);
        long deadlineSeconds = java.time.temporal.ChronoUnit.SECONDS.between(qe.getJoinedAt(), deadline);
        assertThat(deadlineSeconds).isLessThan(1800);
    }

    // ==================== 风险等级判定 ====================

    @Test @DisplayName("风险等级正确划分")
    void riskLevelClassification() {
        assertThat(SlaRiskLevel.fromScore(0, 30, 60, 85)).isEqualTo(SlaRiskLevel.LOW);
        assertThat(SlaRiskLevel.fromScore(29, 30, 60, 85)).isEqualTo(SlaRiskLevel.LOW);
        assertThat(SlaRiskLevel.fromScore(30, 30, 60, 85)).isEqualTo(SlaRiskLevel.MEDIUM);
        assertThat(SlaRiskLevel.fromScore(59, 30, 60, 85)).isEqualTo(SlaRiskLevel.MEDIUM);
        assertThat(SlaRiskLevel.fromScore(60, 30, 60, 85)).isEqualTo(SlaRiskLevel.HIGH);
        assertThat(SlaRiskLevel.fromScore(84, 30, 60, 85)).isEqualTo(SlaRiskLevel.HIGH);
        assertThat(SlaRiskLevel.fromScore(85, 30, 60, 85)).isEqualTo(SlaRiskLevel.CRITICAL);
        assertThat(SlaRiskLevel.fromScore(100, 30, 60, 85)).isEqualTo(SlaRiskLevel.CRITICAL);
    }

    // ==================== 风险扫描与推送 ====================

    @Test @DisplayName("风险等级变化时发布MQ消息")
    void riskLevelChangeShouldPublish() {
        QueueEntry qe = qe(1L, 1L, LocalDateTime.now().minusMinutes(20));
        qe.setRiskLevel("LOW"); // 之前是 LOW
        qe.setSlaDeadline(qe.getJoinedAt().plusMinutes(30));
        when(queueEntryMapper.selectAll()).thenReturn(List.of(qe));
        when(agentMapper.selectBySkillGroupId(1L)).thenReturn(Collections.emptyList());

        predictor.scanRisk();

        // 20分钟等待 / 30分钟SLA + 无客服 -> 风险应大于 LOW
        verify(messageQueue, atLeastOnce()).publish(eq(MessageQueue.Topics.SLA_RISK_CHANGED), anyString());
    }

    @Test @DisplayName("风险等级未变化时不发布消息")
    void noPublishWhenRiskLevelUnchanged() {
        QueueEntry qe = qe(1L, 1L, LocalDateTime.now());
        qe.setRiskLevel("LOW");
        qe.setSlaDeadline(qe.getJoinedAt().plusMinutes(30));
        Agent agent = agent(1L, 1L);
        when(queueEntryMapper.selectAll()).thenReturn(List.of(qe));
        when(agentMapper.selectBySkillGroupId(1L)).thenReturn(List.of(agent));
        when(redisService.isAgentAvailable(1L)).thenReturn(true);
        when(redisService.hasCapacity(1L)).thenReturn(true);
        when(redisService.isHeartbeatAlive(1L)).thenReturn(true);
        when(queueEntryMapper.selectBySkillGroupId(1L)).thenReturn(List.of(qe));

        predictor.scanRisk();

        verify(messageQueue, never()).publish(eq(MessageQueue.Topics.SLA_RISK_CHANGED), anyString());
    }

    // ==================== 辅助方法 ====================

    private QueueEntry qe(long sessionId, long skillGroupId, LocalDateTime joinedAt) {
        QueueEntry q = new QueueEntry();
        q.setSessionId(sessionId);
        q.setSkillGroupId(skillGroupId);
        q.setCustomerId(100L);
        q.setPriorityScore(0);
        q.setRiskScore(0);
        q.setRiskLevel("LOW");
        q.setJoinedAt(joinedAt);
        return q;
    }

    private Agent agent(long id, long skillGroupId) {
        Agent a = new Agent();
        a.setId(id);
        a.setSkillGroupId(skillGroupId);
        a.setMaxCapacity(5);
        a.setStatus("ONLINE");
        return a;
    }
}
