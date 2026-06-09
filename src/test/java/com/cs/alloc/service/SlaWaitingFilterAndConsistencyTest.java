package com.cs.alloc.service;

import com.cs.alloc.config.SlaRiskProperties;
import com.cs.alloc.domain.*;
import com.cs.alloc.mapper.*;
import com.cs.alloc.ws.WsEventPusher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 综合测试: SLA 动态重排队的 WAITING 状态过滤、事件顺序和 WS 风险推送一致性。
 *
 * 场景覆盖:
 * 1. 多技能组并发入队 — 只重排 WAITING 会话
 * 2. 客服离线 — 降级迁移跳过 ASSIGNED/ACTIVE 会话
 * 3. VIP 插队 — 非 WAITING 会话被拒绝
 * 4. 人工置顶 — 非 WAITING 会话被拒绝
 * 5. 快照恢复 — 已接入会话不被塞回等待队列
 * 6. WS 风险推送一致性 — 推送数据与 SlaRiskHistory 一致
 * 7. 事件顺序 — DB insert → MQ publish → WS push
 */
@ExtendWith(MockitoExtension.class)
class SlaWaitingFilterAndConsistencyTest {

    @Mock private QueueEntryMapper queueEntryMapper;
    @Mock private AuditLogMapper auditLogMapper;
    @Mock private SessionMapper sessionMapper;
    @Mock private AgentMapper agentMapper;
    @Mock private CustomerMapper customerMapper;
    @Mock private SlaRiskHistoryMapper slaRiskHistoryMapper;
    @Mock private SkillGroupMapper skillGroupMapper;
    @Mock private RedisService redisService;
    @Mock private SlaRiskCalculator slaRiskCalculator;
    @Mock private MessageQueue messageQueue;
    @Mock private WsEventPusher wsEventPusher;
    @Mock private QueueSnapshotService snapshotService;
    private SlaRiskProperties properties;

    @BeforeEach
    void setUp() {
        properties = new SlaRiskProperties();
        properties.setDegradationTimeoutSeconds(0);
        properties.setFallbackSkillGroups(Map.of(1L, 99L, 2L, 99L, 3L, 98L));
        properties.setSnapshotIntervalSeconds(0);
        properties.setRiskThreshold(75.0);
    }

    // ==============================
    // 1. 多技能组并发入队 — WAITING 过滤
    // ==============================
    @Nested
    @DisplayName("多技能组并发入队: WAITING状态过滤")
    class MultiSkillGroupConcurrentEnqueue {

        @Test @DisplayName("重排时只处理WAITING会话, ASSIGNED/ACTIVE会话被跳过")
        void reorderOnlyWaitingSessions() {
            QueueReorderService service = new QueueReorderService(
                    queueEntryMapper, auditLogMapper, redisService, slaRiskCalculator, messageQueue, sessionMapper);
            when(redisService.tryLock(anyString(), any())).thenReturn("owner1");

            // 3 sessions: 1=WAITING, 2=ASSIGNED, 3=ACTIVE
            Map<Long, SlaRiskScore> scores = new LinkedHashMap<>();
            scores.put(1L, risk(1L, 80.0));
            scores.put(2L, risk(2L, 90.0)); // ASSIGNED — should be skipped
            scores.put(3L, risk(3L, 60.0)); // ACTIVE — should be skipped
            when(slaRiskCalculator.calculateSkillGroupRisks(1L)).thenReturn(scores);

            when(sessionMapper.selectStatus(1L)).thenReturn("WAITING");
            when(sessionMapper.selectStatus(2L)).thenReturn("ASSIGNED");
            when(sessionMapper.selectStatus(3L)).thenReturn("ACTIVE");

            boolean result = service.reorderQueueByRisk(1L);
            assertThat(result).isTrue();

            // Only session 1 should be reordered
            verify(queueEntryMapper).updatePosition(eq(1L), eq(1));
            verify(queueEntryMapper, never()).updatePosition(eq(2L), anyInt());
            verify(queueEntryMapper, never()).updatePosition(eq(3L), anyInt());

            // Audit should record skipped sessions
            verify(auditLogMapper).insert(argThat(a -> "REORDER_SKIP_NON_WAITING".equals(a.getAction())));
            verify(auditLogMapper).insert(argThat(a -> "QUEUE_REORDER".equals(a.getAction())
                    && a.getDetail().contains("跳过2个非WAITING")));
        }

        @Test @DisplayName("所有会话都非WAITING时, 不执行重排")
        void allNonWaitingNoReorder() {
            QueueReorderService service = new QueueReorderService(
                    queueEntryMapper, auditLogMapper, redisService, slaRiskCalculator, messageQueue, sessionMapper);
            when(redisService.tryLock(anyString(), any())).thenReturn("owner1");

            Map<Long, SlaRiskScore> scores = new LinkedHashMap<>();
            scores.put(1L, risk(1L, 80.0));
            scores.put(2L, risk(2L, 90.0));
            when(slaRiskCalculator.calculateSkillGroupRisks(1L)).thenReturn(scores);

            when(sessionMapper.selectStatus(1L)).thenReturn("ASSIGNED");
            when(sessionMapper.selectStatus(2L)).thenReturn("ACTIVE");

            boolean result = service.reorderQueueByRisk(1L);
            assertThat(result).isFalse();

            verify(queueEntryMapper, never()).updatePosition(anyLong(), anyInt());
            verify(queueEntryMapper, never()).updatePriorityScore(anyLong(), anyInt());
        }

        @Test @DisplayName("多技能组独立重排: 各组只处理自己的WAITING会话")
        void multiGroupIndependentReorder() {
            QueueReorderService service = new QueueReorderService(
                    queueEntryMapper, auditLogMapper, redisService, slaRiskCalculator, messageQueue, sessionMapper);
            when(redisService.tryLock(anyString(), any())).thenReturn("owner1");

            // Group 1: session 10 WAITING, session 11 ASSIGNED
            Map<Long, SlaRiskScore> scores1 = new LinkedHashMap<>();
            scores1.put(10L, risk(10L, 80.0));
            scores1.put(11L, risk(11L, 90.0));
            when(slaRiskCalculator.calculateSkillGroupRisks(1L)).thenReturn(scores1);
            when(sessionMapper.selectStatus(10L)).thenReturn("WAITING");
            when(sessionMapper.selectStatus(11L)).thenReturn("ASSIGNED");

            // Group 2: session 20 WAITING, session 21 WAITING
            Map<Long, SlaRiskScore> scores2 = new LinkedHashMap<>();
            scores2.put(20L, risk(20L, 95.0));
            scores2.put(21L, risk(21L, 70.0));
            when(slaRiskCalculator.calculateSkillGroupRisks(2L)).thenReturn(scores2);
            when(sessionMapper.selectStatus(20L)).thenReturn("WAITING");
            when(sessionMapper.selectStatus(21L)).thenReturn("WAITING");

            service.reorderQueueByRisk(1L);
            service.reorderQueueByRisk(2L);

            // Group 1: only session 10 reordered
            verify(queueEntryMapper).updatePosition(eq(10L), eq(1));
            verify(queueEntryMapper, never()).updatePosition(eq(11L), anyInt());

            // Group 2: both sessions reordered (20 first because higher risk)
            verify(queueEntryMapper).updatePosition(eq(20L), eq(1));
            verify(queueEntryMapper).updatePosition(eq(21L), eq(2));
        }
    }

    // ==============================
    // 2. 客服离线 — 降级跳过已接入会话
    // ==============================
    @Nested
    @DisplayName("客服离线: 降级迁移只处理WAITING会话")
    class AgentOfflineDegradation {

        @Test @DisplayName("降级时跳过ASSIGNED/ACTIVE会话, 只迁移WAITING")
        void degradationSkipsAssignedActive() {
            SkillGroupDegradationService service = new SkillGroupDegradationService(
                    queueEntryMapper, agentMapper, auditLogMapper, redisService, messageQueue,
                    properties, sessionMapper);
            when(agentMapper.selectBySkillGroupId(1L)).thenReturn(Collections.emptyList());

            // 3 entries: 1=WAITING, 2=ASSIGNED, 3=ACTIVE
            QueueEntry e1 = qe(1L, 1L, 100L);
            QueueEntry e2 = qe(2L, 1L, 200L);
            QueueEntry e3 = qe(3L, 1L, 300L);
            when(queueEntryMapper.selectBySkillGroupId(1L)).thenReturn(List.of(e1, e2, e3));
            when(sessionMapper.selectStatus(1L)).thenReturn("WAITING");
            when(sessionMapper.selectStatus(2L)).thenReturn("ASSIGNED");
            when(sessionMapper.selectStatus(3L)).thenReturn("ACTIVE");

            boolean result = service.checkAndDegrade(1L);
            assertThat(result).isTrue();

            // Only session 1 should be migrated
            verify(queueEntryMapper).updateSkillGroupId(eq(1L), eq(99L), eq(1L));
            verify(queueEntryMapper, never()).updateSkillGroupId(eq(2L), anyLong(), anyLong());
            verify(queueEntryMapper, never()).updateSkillGroupId(eq(3L), anyLong(), anyLong());

            // Redis: only session 1 removed from old queue and added to new
            verify(redisService).removeFromQueue(eq(1L), eq(1L));
            verify(redisService).addToQueue(eq(99L), eq(1L), anyDouble());
            verify(redisService, never()).removeFromQueue(eq(1L), eq(2L));
            verify(redisService, never()).removeFromQueue(eq(1L), eq(3L));

            // Audit should mention skipped sessions
            verify(auditLogMapper).insert(argThat(a ->
                    "SKILL_GROUP_DEGRADE".equals(a.getAction())
                    && a.getDetail().contains("跳过2个非WAITING")));
        }

        @Test @DisplayName("所有会话都非WAITING时, 不触发降级迁移")
        void allNonWaitingNoDegradation() {
            SkillGroupDegradationService service = new SkillGroupDegradationService(
                    queueEntryMapper, agentMapper, auditLogMapper, redisService, messageQueue,
                    properties, sessionMapper);
            when(agentMapper.selectBySkillGroupId(1L)).thenReturn(Collections.emptyList());

            QueueEntry e1 = qe(1L, 1L, 100L);
            when(queueEntryMapper.selectBySkillGroupId(1L)).thenReturn(List.of(e1));
            when(sessionMapper.selectStatus(1L)).thenReturn("ACTIVE");

            boolean result = service.checkAndDegrade(1L);
            assertThat(result).isFalse();

            verify(queueEntryMapper, never()).updateSkillGroupId(anyLong(), anyLong(), anyLong());
            verify(redisService, never()).addToQueue(eq(99L), anyLong(), anyDouble());
        }

        @Test @DisplayName("恢复时只迁回WAITING会话")
        void restoreOnlyWaitingSessions() {
            SkillGroupDegradationService service = new SkillGroupDegradationService(
                    queueEntryMapper, agentMapper, auditLogMapper, redisService, messageQueue,
                    properties, sessionMapper);

            QueueEntry e1 = qe(1L, 99L, 100L); e1.setOriginalSkillGroupId(1L);
            QueueEntry e2 = qe(2L, 99L, 200L); e2.setOriginalSkillGroupId(1L);
            when(queueEntryMapper.selectByOriginalSkillGroupId(1L)).thenReturn(List.of(e1, e2));
            when(sessionMapper.selectStatus(1L)).thenReturn("WAITING");
            when(sessionMapper.selectStatus(2L)).thenReturn("ASSIGNED"); // already picked up

            boolean result = service.restore(1L);
            assertThat(result).isTrue();

            // Only session 1 restored
            verify(queueEntryMapper).updateSkillGroupId(eq(1L), eq(1L), isNull());
            verify(queueEntryMapper, never()).updateSkillGroupId(eq(2L), anyLong(), any());
            verify(redisService).removeFromQueue(eq(99L), eq(1L));
            verify(redisService).addToQueue(eq(1L), eq(1L), anyDouble());
            verify(redisService, never()).addToQueue(eq(1L), eq(2L), anyDouble());
        }
    }

    // ==============================
    // 3. VIP 插队 — 非 WAITING 被拒绝
    // ==============================
    @Nested
    @DisplayName("VIP插队: 仅WAITING会话可执行")
    class VipJumpFilter {

        @Test @DisplayName("ASSIGNED会话不能VIP插队")
        void assignedSessionRejected() {
            QueueReorderService service = new QueueReorderService(
                    queueEntryMapper, auditLogMapper, redisService, slaRiskCalculator, messageQueue, sessionMapper);
            QueueEntry entry = qe(1L, 1L, 100L);
            entry.setPriorityScore(50);
            when(queueEntryMapper.selectBySessionId(1L)).thenReturn(entry);
            when(sessionMapper.selectStatus(1L)).thenReturn("ASSIGNED");

            assertThatThrownBy(() -> service.applyVipJump(1L, 3, "system"))
                    .isInstanceOf(com.cs.alloc.common.BizException.class)
                    .hasMessageContaining("ASSIGNED");

            verify(queueEntryMapper, never()).updatePriorityScore(anyLong(), anyInt());
        }

        @Test @DisplayName("ACTIVE会话不能VIP插队")
        void activeSessionRejected() {
            QueueReorderService service = new QueueReorderService(
                    queueEntryMapper, auditLogMapper, redisService, slaRiskCalculator, messageQueue, sessionMapper);
            QueueEntry entry = qe(1L, 1L, 100L);
            when(queueEntryMapper.selectBySessionId(1L)).thenReturn(entry);
            when(sessionMapper.selectStatus(1L)).thenReturn("ACTIVE");

            assertThatThrownBy(() -> service.applyVipJump(1L, 5, "system"))
                    .isInstanceOf(com.cs.alloc.common.BizException.class)
                    .hasMessageContaining("ACTIVE");
        }

        @Test @DisplayName("WAITING会话正常VIP插队")
        void waitingSessionVipJumpOk() {
            QueueReorderService service = new QueueReorderService(
                    queueEntryMapper, auditLogMapper, redisService, slaRiskCalculator, messageQueue, sessionMapper);
            QueueEntry entry = qe(1L, 1L, 100L);
            entry.setPriorityScore(50);
            when(queueEntryMapper.selectBySessionId(1L)).thenReturn(entry);
            when(sessionMapper.selectStatus(1L)).thenReturn("WAITING");

            service.applyVipJump(1L, 3, "system");

            verify(queueEntryMapper).updatePriorityScore(1L, 80);
            verify(auditLogMapper).insert(argThat(a -> "VIP_JUMP".equals(a.getAction())));
        }
    }

    // ==============================
    // 4. 人工置顶 — 非 WAITING 被拒绝
    // ==============================
    @Nested
    @DisplayName("人工置顶: 仅WAITING会话可执行")
    class ManualPinFilter {

        @Test @DisplayName("ASSIGNED会话不能置顶")
        void assignedSessionCannotBePinned() {
            QueueReorderService service = new QueueReorderService(
                    queueEntryMapper, auditLogMapper, redisService, slaRiskCalculator, messageQueue, sessionMapper);
            QueueEntry entry = qe(1L, 1L, 100L);
            when(queueEntryMapper.selectBySessionId(1L)).thenReturn(entry);
            when(sessionMapper.selectStatus(1L)).thenReturn("ASSIGNED");

            assertThatThrownBy(() -> service.pinSession(1L, "admin"))
                    .isInstanceOf(com.cs.alloc.common.BizException.class)
                    .hasMessageContaining("ASSIGNED");

            verify(queueEntryMapper, never()).updatePinned(anyLong(), anyBoolean());
            verify(redisService, never()).setPinnedFlag(anyLong(), any());
        }

        @Test @DisplayName("CLOSED会话不能置顶")
        void closedSessionCannotBePinned() {
            QueueReorderService service = new QueueReorderService(
                    queueEntryMapper, auditLogMapper, redisService, slaRiskCalculator, messageQueue, sessionMapper);
            QueueEntry entry = qe(1L, 1L, 100L);
            when(queueEntryMapper.selectBySessionId(1L)).thenReturn(entry);
            when(sessionMapper.selectStatus(1L)).thenReturn("CLOSED");

            assertThatThrownBy(() -> service.pinSession(1L, "admin"))
                    .isInstanceOf(com.cs.alloc.common.BizException.class)
                    .hasMessageContaining("CLOSED");
        }

        @Test @DisplayName("ASSIGNED会话不能取消置顶")
        void assignedSessionCannotBeUnpinned() {
            QueueReorderService service = new QueueReorderService(
                    queueEntryMapper, auditLogMapper, redisService, slaRiskCalculator, messageQueue, sessionMapper);
            QueueEntry entry = qe(1L, 1L, 100L);
            when(queueEntryMapper.selectBySessionId(1L)).thenReturn(entry);
            when(sessionMapper.selectStatus(1L)).thenReturn("ASSIGNED");

            assertThatThrownBy(() -> service.unpinSession(1L, "admin"))
                    .isInstanceOf(com.cs.alloc.common.BizException.class)
                    .hasMessageContaining("ASSIGNED");
        }

        @Test @DisplayName("WAITING会话正常置顶")
        void waitingSessionPinOk() {
            QueueReorderService service = new QueueReorderService(
                    queueEntryMapper, auditLogMapper, redisService, slaRiskCalculator, messageQueue, sessionMapper);
            QueueEntry entry = qe(1L, 1L, 100L);
            when(queueEntryMapper.selectBySessionId(1L)).thenReturn(entry);
            when(sessionMapper.selectStatus(1L)).thenReturn("WAITING");

            service.pinSession(1L, "admin");

            verify(queueEntryMapper).updatePinned(1L, true);
            verify(queueEntryMapper).updatePriorityScore(1L, Integer.MAX_VALUE);
            verify(redisService).updateQueueEntryScore(1L, 1L, Double.MAX_VALUE);
            verify(redisService).setPinnedFlag(eq(1L), any());
        }
    }

    // ==============================
    // 5. 快照恢复 — 已接入会话不被塞回
    // ==============================
    @Nested
    @DisplayName("快照恢复: 已接入会话不被塞回等待队列")
    class SnapshotRecoveryFilter {

        @Test @DisplayName("恢复时跳过ASSIGNED会话")
        void recoverSkipsAssignedSessions() {
            QueueSnapshotService service = new QueueSnapshotService(
                    queueEntryMapper, redisService, slaRiskCalculator, sessionMapper);

            QueueEntry e1 = qe(1L, 1L, 100L);
            QueueEntry e2 = qe(2L, 1L, 200L);
            QueueEntry e3 = qe(3L, 1L, 300L);
            QueueSnapshot snap = QueueSnapshot.builder()
                    .snapshotId("test").skillGroupId(1L).entries(List.of(e1, e2, e3))
                    .capturedAt(System.currentTimeMillis()).totalEntries(3).avgRiskScore(50.0).build();

            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper()
                        .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
                when(redisService.getQueueSnapshot(1L)).thenReturn(Optional.of(mapper.writeValueAsString(snap)));
            } catch (Exception e) { throw new RuntimeException(e); }

            when(queueEntryMapper.selectBySessionId(1L)).thenReturn(e1);
            when(queueEntryMapper.selectBySessionId(2L)).thenReturn(e2);
            when(queueEntryMapper.selectBySessionId(3L)).thenReturn(e3);
            when(sessionMapper.selectStatus(1L)).thenReturn("WAITING");
            when(sessionMapper.selectStatus(2L)).thenReturn("ASSIGNED"); // already assigned
            when(sessionMapper.selectStatus(3L)).thenReturn("ACTIVE");   // already active

            int recovered = service.recoverFromSnapshot(1L);

            // Only session 1 should be recovered
            assertThat(recovered).isEqualTo(1);
            verify(redisService).addToQueue(eq(1L), eq(1L), anyDouble());
            verify(redisService, never()).addToQueue(eq(1L), eq(2L), anyDouble());
            verify(redisService, never()).addToQueue(eq(1L), eq(3L), anyDouble());
        }

        @Test @DisplayName("恢复时跳过已从DB删除的会话")
        void recoverSkipsDeletedSessions() {
            QueueSnapshotService service = new QueueSnapshotService(
                    queueEntryMapper, redisService, slaRiskCalculator, sessionMapper);

            QueueEntry e1 = qe(1L, 1L, 100L);
            QueueEntry e2 = qe(2L, 1L, 200L);
            QueueSnapshot snap = QueueSnapshot.builder()
                    .snapshotId("test").skillGroupId(1L).entries(List.of(e1, e2))
                    .capturedAt(System.currentTimeMillis()).totalEntries(2).avgRiskScore(50.0).build();

            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper()
                        .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
                when(redisService.getQueueSnapshot(1L)).thenReturn(Optional.of(mapper.writeValueAsString(snap)));
            } catch (Exception e) { throw new RuntimeException(e); }

            when(queueEntryMapper.selectBySessionId(1L)).thenReturn(e1);
            when(queueEntryMapper.selectBySessionId(2L)).thenReturn(null); // deleted from DB
            when(sessionMapper.selectStatus(1L)).thenReturn("WAITING");

            int recovered = service.recoverFromSnapshot(1L);
            assertThat(recovered).isEqualTo(1);
            verify(redisService).addToQueue(eq(1L), eq(1L), anyDouble());
        }

        @Test @DisplayName("快照保存时只包含WAITING会话")
        void saveSnapshotOnlyWaitingSessions() {
            QueueSnapshotService service = new QueueSnapshotService(
                    queueEntryMapper, redisService, slaRiskCalculator, sessionMapper);

            QueueEntry e1 = qe(1L, 1L, 100L);
            QueueEntry e2 = qe(2L, 1L, 200L);
            when(queueEntryMapper.selectBySkillGroupId(1L)).thenReturn(List.of(e1, e2));
            when(sessionMapper.selectStatus(1L)).thenReturn("WAITING");
            when(sessionMapper.selectStatus(2L)).thenReturn("ASSIGNED");
            when(slaRiskCalculator.calculateSkillGroupRisks(1L))
                    .thenReturn(Map.of(1L, risk(1L, 50.0), 2L, risk(2L, 60.0)));

            QueueSnapshot snapshot = service.saveSnapshot(1L, Duration.ofSeconds(300));

            // Only session 1 in snapshot
            assertThat(snapshot.getTotalEntries()).isEqualTo(1);
            assertThat(snapshot.getEntries()).hasSize(1);
            assertThat(snapshot.getEntries().get(0).getSessionId()).isEqualTo(1L);
        }
    }

    // ==============================
    // 6. WS 风险推送一致性
    // ==============================
    @Nested
    @DisplayName("WS风险推送与SlaRiskHistory一致性")
    class WsRiskPushConsistency {

        @Test @DisplayName("推送数据与DB一致时验证通过")
        void pushedDataMatchesDb() {
            WsEventPusher pusher = new WsEventPusher(slaRiskHistoryMapper);

            SlaRiskHistory dbHistory = new SlaRiskHistory();
            dbHistory.setSessionId(1L);
            dbHistory.setRiskScore(85.0);
            when(slaRiskHistoryMapper.selectLatestBySessionId(1L)).thenReturn(dbHistory);

            Map<String, Object> data = new HashMap<>();
            List<Map<String, Object>> sessionRisks = new ArrayList<>();
            sessionRisks.add(Map.of("sessionId", 1L, "riskScore", 85.0));
            data.put("sessionRisks", sessionRisks);

            boolean result = pusher.validateRiskConsistency(data);
            assertThat(result).isTrue();
        }

        @Test @DisplayName("推送数据与DB不一致时验证失败并记录告警")
        void pushedDataMismatchWithDb() {
            WsEventPusher pusher = new WsEventPusher(slaRiskHistoryMapper);

            SlaRiskHistory dbHistory = new SlaRiskHistory();
            dbHistory.setSessionId(1L);
            dbHistory.setRiskScore(60.0); // DB has 60
            when(slaRiskHistoryMapper.selectLatestBySessionId(1L)).thenReturn(dbHistory);

            Map<String, Object> data = new HashMap<>();
            List<Map<String, Object>> sessionRisks = new ArrayList<>();
            sessionRisks.add(Map.of("sessionId", 1L, "riskScore", 95.0)); // Pushed 95, mismatch!
            data.put("sessionRisks", sessionRisks);

            boolean result = pusher.validateRiskConsistency(data);
            assertThat(result).isFalse();
        }

        @Test @DisplayName("DB无记录时验证通过(无法比对)")
        void noDbRecordPassesValidation() {
            WsEventPusher pusher = new WsEventPusher(slaRiskHistoryMapper);

            when(slaRiskHistoryMapper.selectLatestBySessionId(1L)).thenReturn(null);

            Map<String, Object> data = new HashMap<>();
            List<Map<String, Object>> sessionRisks = new ArrayList<>();
            sessionRisks.add(Map.of("sessionId", 1L, "riskScore", 85.0));
            data.put("sessionRisks", sessionRisks);

            boolean result = pusher.validateRiskConsistency(data);
            assertThat(result).isTrue();
        }

        @Test @DisplayName("空sessionRisks列表验证通过")
        void emptySessionRisksPassesValidation() {
            WsEventPusher pusher = new WsEventPusher(slaRiskHistoryMapper);

            Map<String, Object> data = new HashMap<>();
            data.put("sessionRisks", Collections.emptyList());

            boolean result = pusher.validateRiskConsistency(data);
            assertThat(result).isTrue();
        }

        @Test @DisplayName("无sessionRisks字段验证通过")
        void noSessionRisksFieldPassesValidation() {
            WsEventPusher pusher = new WsEventPusher(slaRiskHistoryMapper);

            Map<String, Object> data = new HashMap<>();
            data.put("criticalAlert", true);

            boolean result = pusher.validateRiskConsistency(data);
            assertThat(result).isTrue();
        }

        @Test @DisplayName("多session风险校验: 部分不一致")
        void multiSessionPartialMismatch() {
            WsEventPusher pusher = new WsEventPusher(slaRiskHistoryMapper);

            SlaRiskHistory h1 = new SlaRiskHistory();
            h1.setSessionId(1L); h1.setRiskScore(80.0);
            SlaRiskHistory h2 = new SlaRiskHistory();
            h2.setSessionId(2L); h2.setRiskScore(50.0); // DB=50, pushed=90 → mismatch
            when(slaRiskHistoryMapper.selectLatestBySessionId(1L)).thenReturn(h1);
            when(slaRiskHistoryMapper.selectLatestBySessionId(2L)).thenReturn(h2);

            Map<String, Object> data = new HashMap<>();
            List<Map<String, Object>> sessionRisks = new ArrayList<>();
            sessionRisks.add(Map.of("sessionId", 1L, "riskScore", 80.0)); // match
            sessionRisks.add(Map.of("sessionId", 2L, "riskScore", 90.0)); // mismatch
            data.put("sessionRisks", sessionRisks);

            boolean result = pusher.validateRiskConsistency(data);
            assertThat(result).isFalse(); // At least one mismatch
        }
    }

    // ==============================
    // 7. SlaRiskEngine 事件顺序
    // ==============================
    @Nested
    @DisplayName("SlaRiskEngine: DB写入先于MQ推送")
    class SlaRiskEngineEventOrdering {

        @Test @DisplayName("风险历史先于MQ推送写入, 推送数据包含逐会话风险")
        void historySavedBeforeMqPublish() {
            // Track call order
            List<String> callOrder = new CopyOnWriteArrayList<>();

            doAnswer(inv -> { callOrder.add("db_insert:" + ((SlaRiskHistory) inv.getArgument(0)).getSessionId()); return null; })
                    .when(slaRiskHistoryMapper).insert(any(SlaRiskHistory.class));
            doAnswer(inv -> { callOrder.add("mq_publish"); return null; })
                    .when(messageQueue).publish(eq(MessageQueue.Topics.SLA_RISK_UPDATED), anyString());

            SkillGroup g1 = sg(1L);
            when(skillGroupMapper.selectAllActive()).thenReturn(List.of(g1));
            SlaRiskScore score = SlaRiskScore.builder()
                    .sessionId(1L).riskScore(60.0).vipLevel(0).waitSeconds(100L)
                    .availableAgents(2).avgAgentLoad(1.5).calculatedAt(System.currentTimeMillis())
                    .build();
            when(slaRiskCalculator.calculateSkillGroupRisks(1L)).thenReturn(Map.of(1L, score));
            when(snapshotService.saveSnapshot(anyLong(), any())).thenReturn(QueueSnapshot.builder().build());

            SlaRiskEngine engine = new SlaRiskEngine(skillGroupMapper, slaRiskHistoryMapper,
                    slaRiskCalculator, mock(QueueReorderService.class),
                    mock(SkillGroupDegradationService.class), snapshotService,
                    messageQueue, wsEventPusher, properties);
            ReflectionTestUtils.setField(engine, "lastSnapshotTime", 0L);

            engine.runSlaCycle();

            // DB insert must come before MQ publish
            assertThat(callOrder).containsExactly("db_insert:1", "mq_publish");
        }

        @Test @DisplayName("MQ推送包含逐会话风险数据")
        void mqPublishContainsPerSessionRiskData() {
            SkillGroup g1 = sg(1L);
            when(skillGroupMapper.selectAllActive()).thenReturn(List.of(g1));
            SlaRiskScore score = SlaRiskScore.builder()
                    .sessionId(42L).riskScore(88.0).vipLevel(2).waitSeconds(200L)
                    .availableAgents(1).avgAgentLoad(3.0).calculatedAt(System.currentTimeMillis())
                    .skillGroupId(1L)
                    .build();
            when(slaRiskCalculator.calculateSkillGroupRisks(1L)).thenReturn(Map.of(42L, score));
            when(snapshotService.saveSnapshot(anyLong(), any())).thenReturn(QueueSnapshot.builder().build());

            SlaRiskEngine engine = new SlaRiskEngine(skillGroupMapper, slaRiskHistoryMapper,
                    slaRiskCalculator, mock(QueueReorderService.class),
                    mock(SkillGroupDegradationService.class), snapshotService,
                    messageQueue, wsEventPusher, properties);
            ReflectionTestUtils.setField(engine, "lastSnapshotTime", 0L);

            engine.runSlaCycle();

            // Verify MQ message contains session risk details
            ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
            verify(messageQueue).publish(eq(MessageQueue.Topics.SLA_RISK_UPDATED), msgCaptor.capture());
            String msg = msgCaptor.getValue();
            assertThat(msg).contains("sessionRisks");
            assertThat(msg).contains("sessionId");
            assertThat(msg).contains("42");
            assertThat(msg).contains("88.0");
        }

        @Test @DisplayName("高风险先存DB再重排再推送")
        void highRiskOrdering_dbThenReorderThenPublish() {
            List<String> callOrder = new CopyOnWriteArrayList<>();

            doAnswer(inv -> { callOrder.add("db_insert"); return null; })
                    .when(slaRiskHistoryMapper).insert(any(SlaRiskHistory.class));

            QueueReorderService mockReorder = mock(QueueReorderService.class);
            when(mockReorder.reorderQueueByRisk(anyLong())).thenAnswer(inv -> {
                callOrder.add("reorder");
                return true;
            });

            doAnswer(inv -> { callOrder.add("mq_publish"); return null; })
                    .when(messageQueue).publish(anyString(), anyString());

            SkillGroup g1 = sg(1L);
            when(skillGroupMapper.selectAllActive()).thenReturn(List.of(g1));
            SlaRiskScore score = SlaRiskScore.builder()
                    .sessionId(1L).riskScore(85.0).vipLevel(0).waitSeconds(100L)
                    .availableAgents(2).avgAgentLoad(1.5).calculatedAt(System.currentTimeMillis())
                    .build();
            when(slaRiskCalculator.calculateSkillGroupRisks(1L)).thenReturn(Map.of(1L, score));
            when(snapshotService.saveSnapshot(anyLong(), any())).thenReturn(QueueSnapshot.builder().build());

            SlaRiskEngine engine = new SlaRiskEngine(skillGroupMapper, slaRiskHistoryMapper,
                    slaRiskCalculator, mockReorder,
                    mock(SkillGroupDegradationService.class), snapshotService,
                    messageQueue, wsEventPusher, properties);
            ReflectionTestUtils.setField(engine, "lastSnapshotTime", 0L);

            engine.runSlaCycle();

            // Order: DB insert → reorder → MQ publish (for SLA_RISK_UPDATED)
            int dbIdx = callOrder.indexOf("db_insert");
            int reorderIdx = callOrder.indexOf("reorder");
            int mqIdx = callOrder.lastIndexOf("mq_publish");
            assertThat(dbIdx).isLessThan(reorderIdx);
            assertThat(reorderIdx).isLessThan(mqIdx);
        }
    }

    // ==============================
    // Helper methods
    // ==============================
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
    private SkillGroup sg(long id) {
        SkillGroup s = new SkillGroup(); s.setId(id); s.setName("G" + id); s.setActive(true); return s;
    }
}
