package com.cs.alloc.service;

import com.cs.alloc.config.SlaRiskProperties;
import com.cs.alloc.domain.*;
import com.cs.alloc.mapper.*;
import com.cs.alloc.ws.WsEventPusher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SlaRiskIntegrationTest {
    @Mock private SkillGroupMapper skillGroupMapper;
    @Mock private SlaRiskHistoryMapper slaRiskHistoryMapper;
    @Mock private SlaRiskCalculator slaRiskCalculator;
    @Mock private QueueReorderService queueReorderService;
    @Mock private SkillGroupDegradationService degradationService;
    @Mock private QueueSnapshotService snapshotService;
    @Mock private MessageQueue messageQueue;
    @Mock private WsEventPusher wsEventPusher;
    @Mock private QueueEntryMapper queueEntryMapper;
    @Mock private AgentMapper agentMapper;
    @Mock private CustomerMapper customerMapper;
    @Mock private AuditLogMapper auditLogMapper;
    @Mock private RedisService redisService;
    @Mock private SessionMapper sessionMapper;
    private SlaRiskProperties properties;

    @BeforeEach
    void setUp() {
        properties = new SlaRiskProperties();
        properties.setDegradationTimeoutSeconds(0);
        properties.setFallbackSkillGroups(Map.of(1L, 99L, 2L, 99L));
        properties.setSnapshotIntervalSeconds(0);
    }

    @Test @DisplayName("多技能组并发入队: 各技能组独立计算风险")
    void multiSkillGroupConcurrentEnqueue() {
        SkillGroup g1 = sg(1L); SkillGroup g2 = sg(2L); SkillGroup g3 = sg(3L);
        when(skillGroupMapper.selectAllActive()).thenReturn(List.of(g1, g2, g3));
        when(slaRiskCalculator.calculateSkillGroupRisks(1L))
                .thenReturn(Map.of(101L, risk(101L, 60.0)));
        when(slaRiskCalculator.calculateSkillGroupRisks(2L))
                .thenReturn(Map.of(201L, risk(201L, 85.0)));
        when(slaRiskCalculator.calculateSkillGroupRisks(3L))
                .thenReturn(Map.of(301L, risk(301L, 40.0)));

        SlaRiskEngine engine = buildEngine();
        engine.runSlaCycle();

        // group 2 has high risk, should reorder
        verify(queueReorderService).reorderQueueByRisk(2L);
        // groups 1 and 3 are below threshold
        verify(queueReorderService, never()).reorderQueueByRisk(1L);
        verify(queueReorderService, never()).reorderQueueByRisk(3L);
    }

    @Test @DisplayName("客服离线触发降级检查")
    void agentOfflineDegradationCheck() {
        SkillGroup g1 = sg(1L);
        when(skillGroupMapper.selectAllActive()).thenReturn(List.of(g1));
        when(slaRiskCalculator.calculateSkillGroupRisks(1L))
                .thenReturn(Map.of(101L, risk(101L, 50.0)));
        when(degradationService.checkAndDegrade(1L)).thenReturn(true);

        SlaRiskEngine engine = buildEngine();
        engine.runSlaCycle();

        verify(degradationService).checkAndDegrade(1L);
    }

    @Test @DisplayName("VIP插队后重排中VIP客户优先")
    void vipJumpThenReorder() {
        QueueEntry vipEntry = qe(1L, 1L, 100L);

        SlaRiskCalculator realCalc = new SlaRiskCalculator(queueEntryMapper, customerMapper,
                agentMapper, redisService, properties);
        QueueReorderService reorderService = new QueueReorderService(
                queueEntryMapper, auditLogMapper, redisService, realCalc, messageQueue, sessionMapper);

        // VIP jump first
        vipEntry.setPriorityScore(50);
        when(queueEntryMapper.selectBySessionId(1L)).thenReturn(vipEntry);
        when(sessionMapper.selectById(1L)).thenReturn(waitingSession(1L));
        reorderService.applyVipJump(1L, 3, "system");

        verify(queueEntryMapper).updatePriorityScore(1L, 80); // 50 + 30
    }

    @Test @DisplayName("SLA超时: 长时间等待风险达到上限")
    void slaTimeoutRiskCapped() {
        QueueEntry longWait = qe(1L, 1L, 100L);
        longWait.setJoinedAt(LocalDateTime.now().minusMinutes(30)); // 30 min wait
        when(customerMapper.selectById(100L)).thenReturn(customer(100L, 3));
        when(agentMapper.selectBySkillGroupId(anyLong())).thenReturn(Collections.emptyList());

        SlaRiskCalculator realCalc = new SlaRiskCalculator(queueEntryMapper, customerMapper,
                agentMapper, redisService, properties);
        SlaRiskScore score = realCalc.calculateRisk(longWait);
        assertThat(score.getRiskScore()).isEqualTo(100.0); // capped
        assertThat(score.getWaitSeconds()).isGreaterThan(1700L);
    }

    @Test @DisplayName("人工置顶覆盖风险评分")
    void manualPinOverridesRisk() {
        QueueEntry entry = qe(1L, 1L, 100L);
        entry.setPinned(true);
        when(customerMapper.selectById(100L)).thenReturn(customer(100L, 0));
        when(agentMapper.selectBySkillGroupId(anyLong())).thenReturn(Collections.emptyList());

        SlaRiskCalculator realCalc = new SlaRiskCalculator(queueEntryMapper, customerMapper,
                agentMapper, redisService, properties);
        SlaRiskScore score = realCalc.calculateRisk(entry);
        assertThat(score.getRiskScore()).isEqualTo(Double.MAX_VALUE);
        assertThat(score.getPinned()).isTrue();
    }

    @Test @DisplayName("多技能组降级: 两个组同时降级 — 仅迁移WAITING")
    void doubleDegradation() {
        SkillGroupDegradationService degradationSvc = new SkillGroupDegradationService(
                queueEntryMapper, agentMapper, auditLogMapper, redisService, messageQueue, properties, sessionMapper);

        when(agentMapper.selectBySkillGroupId(1L)).thenReturn(Collections.emptyList());
        when(agentMapper.selectBySkillGroupId(2L)).thenReturn(Collections.emptyList());
        QueueEntry e1 = qe(1L, 1L, 100L);
        QueueEntry e2 = qe(2L, 2L, 200L);
        when(queueEntryMapper.selectWaitingBySkillGroupId(1L)).thenReturn(List.of(e1));
        when(queueEntryMapper.selectWaitingBySkillGroupId(2L)).thenReturn(List.of(e2));
        when(queueEntryMapper.batchUpdateSkillGroupWaiting(1L, 99L)).thenReturn(1);
        when(queueEntryMapper.batchUpdateSkillGroupWaiting(2L, 99L)).thenReturn(1);

        boolean r1 = degradationSvc.checkAndDegrade(1L);
        boolean r2 = degradationSvc.checkAndDegrade(2L);
        assertThat(r1).isTrue();
        assertThat(r2).isTrue();
        verify(messageQueue, times(2)).publish(eq(MessageQueue.Topics.SKILLGROUP_DEGRADED), anyString());
    }

    // ===== 新增: 多技能组并发入队一致性 =====

    @Test @DisplayName("多技能组并发入队: history先落库再推送事件")
    void multiGroupHistoryBeforeEvent() {
        SkillGroup g1 = sg(1L); SkillGroup g2 = sg(2L);
        when(skillGroupMapper.selectAllActive()).thenReturn(List.of(g1, g2));
        when(slaRiskCalculator.calculateSkillGroupRisks(1L))
                .thenReturn(Map.of(101L, risk(101L, 80.0)));
        when(slaRiskCalculator.calculateSkillGroupRisks(2L))
                .thenReturn(Map.of(201L, risk(201L, 50.0)));

        SlaRiskEngine engine = buildEngine();
        engine.runSlaCycle();

        // History must be saved for both groups before the unified event push
        var inOrder = inOrder(slaRiskHistoryMapper, messageQueue);
        inOrder.verify(slaRiskHistoryMapper, times(2)).insert(any());
        inOrder.verify(messageQueue).publish(eq(MessageQueue.Topics.SLA_RISK_UPDATED), anyString());
    }

    @Test @DisplayName("VIP插队对ASSIGNED会话拒绝操作")
    void vipJumpOnAssignedSessionRejected() {
        QueueEntry entry = qe(1L, 1L, 100L);
        entry.setPriorityScore(50);

        SlaRiskCalculator realCalc = new SlaRiskCalculator(queueEntryMapper, customerMapper,
                agentMapper, redisService, properties);
        QueueReorderService reorderService = new QueueReorderService(
                queueEntryMapper, auditLogMapper, redisService, realCalc, messageQueue, sessionMapper);

        when(queueEntryMapper.selectBySessionId(1L)).thenReturn(entry);
        Session assigned = new Session();
        assigned.setId(1L);
        assigned.setStatus("ASSIGNED");
        when(sessionMapper.selectById(1L)).thenReturn(assigned);

        assertThatThrownBy(() -> reorderService.applyVipJump(1L, 3, "system"))
                .isInstanceOf(com.cs.alloc.common.BizException.class)
                .hasMessageContaining("WAITING");
    }

    @Test @DisplayName("人工置顶ACTIVE会话被拒绝")
    void pinActiveSessionRejected() {
        QueueEntry entry = qe(1L, 1L, 100L);

        SlaRiskCalculator realCalc = new SlaRiskCalculator(queueEntryMapper, customerMapper,
                agentMapper, redisService, properties);
        QueueReorderService reorderService = new QueueReorderService(
                queueEntryMapper, auditLogMapper, redisService, realCalc, messageQueue, sessionMapper);

        when(queueEntryMapper.selectBySessionId(1L)).thenReturn(entry);
        Session active = new Session();
        active.setId(1L);
        active.setStatus("ACTIVE");
        when(sessionMapper.selectById(1L)).thenReturn(active);

        assertThatThrownBy(() -> reorderService.pinSession(1L, "admin"))
                .isInstanceOf(com.cs.alloc.common.BizException.class)
                .hasMessageContaining("WAITING");
    }

    private SlaRiskEngine buildEngine() {
        SlaRiskEngine engine = new SlaRiskEngine(skillGroupMapper, slaRiskHistoryMapper,
                slaRiskCalculator, queueReorderService, degradationService, snapshotService,
                messageQueue, wsEventPusher, properties);
        ReflectionTestUtils.setField(engine, "lastSnapshotTime", 0L);
        return engine;
    }

    private SkillGroup sg(long id) {
        SkillGroup s = new SkillGroup(); s.setId(id); s.setName("G" + id); s.setActive(true); return s;
    }
    private SlaRiskScore risk(long sessionId, double score) {
        return SlaRiskScore.builder().sessionId(sessionId).riskScore(score)
                .vipLevel(0).waitSeconds(60L).availableAgents(1).avgAgentLoad(1.0)
                .historicalAht(300L).agentHeartbeatOk(true).calculatedAt(System.currentTimeMillis())
                .build();
    }
    private QueueEntry qe(long sid, long sg, long cid) {
        QueueEntry q = new QueueEntry();
        q.setSessionId(sid); q.setSkillGroupId(sg); q.setCustomerId(cid);
        q.setPriorityScore(0); q.setJoinedAt(LocalDateTime.now());
        return q;
    }
    private Customer customer(long id, int vip) {
        Customer c = new Customer(); c.setId(id); c.setVipLevel(vip); return c;
    }
    private Session waitingSession(long id) {
        Session s = new Session();
        s.setId(id);
        s.setStatus("WAITING");
        return s;
    }
}
