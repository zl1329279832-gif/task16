package com.cs.alloc.service;

import com.cs.alloc.common.BizException;
import com.cs.alloc.domain.AuditLog;
import com.cs.alloc.domain.QueueEntry;
import com.cs.alloc.domain.Session;
import com.cs.alloc.domain.SlaRiskScore;
import com.cs.alloc.mapper.AuditLogMapper;
import com.cs.alloc.mapper.QueueEntryMapper;
import com.cs.alloc.mapper.SessionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.LocalDateTime;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class QueueReorderServiceTest {
    @Mock private QueueEntryMapper queueEntryMapper;
    @Mock private AuditLogMapper auditLogMapper;
    @Mock private RedisService redisService;
    @Mock private SlaRiskCalculator slaRiskCalculator;
    @Mock private MessageQueue messageQueue;
    @Mock private SessionMapper sessionMapper;
    private QueueReorderService service;

    @BeforeEach
    void setUp() {
        service = new QueueReorderService(queueEntryMapper, auditLogMapper, redisService,
                slaRiskCalculator, messageQueue, sessionMapper);
    }

    @Test @DisplayName("按风险评分降序重排队列")
    void reorderByRiskDescending() {
        when(redisService.tryLock(anyString(), any())).thenReturn("owner1");
        Map<Long, SlaRiskScore> scores = new LinkedHashMap<>();
        scores.put(1L, risk(1L, 50.0));
        scores.put(2L, risk(2L, 90.0));
        scores.put(3L, risk(3L, 30.0));
        when(slaRiskCalculator.calculateSkillGroupRisks(1L)).thenReturn(scores);
        when(redisService.getQueueMembers(1L)).thenReturn(Set.of("1", "2", "3"));

        boolean result = service.reorderQueueByRisk(1L);
        assertThat(result).isTrue();
        // session 2 (highest risk) should be position 1
        verify(queueEntryMapper).updatePosition(eq(2L), eq(1));
        verify(queueEntryMapper).updatePosition(eq(1L), eq(2));
        verify(queueEntryMapper).updatePosition(eq(3L), eq(3));
        verify(auditLogMapper).insert(any(AuditLog.class));
        verify(messageQueue).publish(eq(MessageQueue.Topics.QUEUE_REORDERED), anyString());
    }

    @Test @DisplayName("空队列不重排")
    void emptyQueueNoReorder() {
        when(redisService.tryLock(anyString(), any())).thenReturn("owner1");
        when(slaRiskCalculator.calculateSkillGroupRisks(1L)).thenReturn(Collections.emptyMap());

        boolean result = service.reorderQueueByRisk(1L);
        assertThat(result).isFalse();
        verify(queueEntryMapper, never()).updatePriorityScore(anyLong(), anyInt());
    }

    @Test @DisplayName("锁获取失败跳过重排")
    void lockFailSkipReorder() {
        when(redisService.tryLock(anyString(), any())).thenReturn(null);

        boolean result = service.reorderQueueByRisk(1L);
        assertThat(result).isFalse();
        verify(slaRiskCalculator, never()).calculateSkillGroupRisks(anyLong());
    }

    @Test @DisplayName("人工置顶会话")
    void pinSession() {
        QueueEntry entry = qe(1L, 1L);
        when(queueEntryMapper.selectBySessionId(1L)).thenReturn(entry);
        when(sessionMapper.selectById(1L)).thenReturn(waitingSession(1L));

        service.pinSession(1L, "admin");

        verify(queueEntryMapper).updatePinned(1L, true);
        verify(queueEntryMapper).updatePriorityScore(1L, Integer.MAX_VALUE);
        verify(redisService).updateQueueEntryScore(1L, 1L, Double.MAX_VALUE);
        verify(redisService).setPinnedFlag(eq(1L), any());
        verify(auditLogMapper).insert(argThat(a -> "SESSION_PIN".equals(a.getAction())));
    }

    @Test @DisplayName("取消置顶")
    void unpinSession() {
        QueueEntry entry = qe(1L, 1L);
        when(queueEntryMapper.selectBySessionId(1L)).thenReturn(entry);

        service.unpinSession(1L, "admin");

        verify(queueEntryMapper).updatePinned(1L, false);
        verify(redisService).clearPinnedFlag(1L);
        verify(auditLogMapper).insert(argThat(a -> "SESSION_UNPIN".equals(a.getAction())));
    }

    @Test @DisplayName("VIP插队增加分数")
    void vipJump() {
        QueueEntry entry = qe(1L, 1L);
        entry.setPriorityScore(50);
        when(queueEntryMapper.selectBySessionId(1L)).thenReturn(entry);
        when(sessionMapper.selectById(1L)).thenReturn(waitingSession(1L));

        service.applyVipJump(1L, 3, "system");

        verify(queueEntryMapper).updatePriorityScore(1L, 80); // 50 + 3*10
        verify(redisService).updateQueueEntryScore(1L, 1L, 80);
        verify(auditLogMapper).insert(argThat(a -> "VIP_JUMP".equals(a.getAction())));
    }

    @Test @DisplayName("置顶不存在会话抛出异常")
    void pinNonExistentThrows() {
        when(queueEntryMapper.selectBySessionId(999L)).thenReturn(null);
        assertThatThrownBy(() -> service.pinSession(999L, "admin"))
                .isInstanceOf(BizException.class);
    }

    @Test @DisplayName("重排记录审计日志")
    void reorderAuditLog() {
        when(redisService.tryLock(anyString(), any())).thenReturn("owner1");
        Map<Long, SlaRiskScore> scores = Map.of(1L, risk(1L, 50.0));
        when(slaRiskCalculator.calculateSkillGroupRisks(1L)).thenReturn(scores);
        when(redisService.getQueueMembers(1L)).thenReturn(Set.of("1"));

        service.reorderQueueByRisk(1L);

        verify(auditLogMapper).insert(argThat(a ->
                "QUEUE_REORDER".equals(a.getAction()) && "SYSTEM".equals(a.getOperatorType())));
    }

    // ===== 新增: 状态过滤相关测试 =====

    @Test @DisplayName("置顶ASSIGNED会话被拒绝")
    void pinAssignedSessionRejected() {
        QueueEntry entry = qe(1L, 1L);
        when(queueEntryMapper.selectBySessionId(1L)).thenReturn(entry);
        Session assigned = new Session();
        assigned.setId(1L);
        assigned.setStatus("ASSIGNED");
        when(sessionMapper.selectById(1L)).thenReturn(assigned);

        assertThatThrownBy(() -> service.pinSession(1L, "admin"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("WAITING");
    }

    @Test @DisplayName("VIP插队ACTIVE会话被拒绝")
    void vipJumpActiveSessionRejected() {
        QueueEntry entry = qe(1L, 1L);
        entry.setPriorityScore(50);
        when(queueEntryMapper.selectBySessionId(1L)).thenReturn(entry);
        Session active = new Session();
        active.setId(1L);
        active.setStatus("ACTIVE");
        when(sessionMapper.selectById(1L)).thenReturn(active);

        assertThatThrownBy(() -> service.applyVipJump(1L, 3, "system"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("WAITING");
    }

    @Test @DisplayName("重排时清理Redis中非WAITING残留条目")
    void reorderEvictsNonWaitingFromRedis() {
        when(redisService.tryLock(anyString(), any())).thenReturn("owner1");
        // calculator只返回WAITING的session 1
        Map<Long, SlaRiskScore> scores = Map.of(1L, risk(1L, 50.0));
        when(slaRiskCalculator.calculateSkillGroupRisks(1L)).thenReturn(scores);
        // 但Redis中还有session 2 (已被分配但未清理)
        when(redisService.getQueueMembers(1L)).thenReturn(Set.of("1", "2"));

        service.reorderQueueByRisk(1L);

        // session 2应被从Redis中清理
        verify(redisService).removeFromQueue(1L, 2L);
        // session 1正常重排
        verify(queueEntryMapper).updatePosition(1L, 1);
    }

    @Test @DisplayName("人工置顶后VIP插队: 置顶优先级不被VIP覆盖")
    void pinThenVipJumpSequence() {
        QueueEntry entry = qe(1L, 1L);
        when(queueEntryMapper.selectBySessionId(1L)).thenReturn(entry);
        when(sessionMapper.selectById(1L)).thenReturn(waitingSession(1L));

        service.pinSession(1L, "admin");

        verify(queueEntryMapper).updatePriorityScore(1L, Integer.MAX_VALUE);
        verify(redisService).updateQueueEntryScore(1L, 1L, Double.MAX_VALUE);
    }

    private SlaRiskScore risk(long sessionId, double score) {
        return SlaRiskScore.builder().sessionId(sessionId).riskScore(score).build();
    }
    private QueueEntry qe(long sid, long sg) {
        QueueEntry q = new QueueEntry();
        q.setSessionId(sid); q.setSkillGroupId(sg); q.setCustomerId(100L);
        q.setPriorityScore(0); q.setJoinedAt(LocalDateTime.now());
        return q;
    }
    private Session waitingSession(long id) {
        Session s = new Session();
        s.setId(id);
        s.setStatus("WAITING");
        return s;
    }
}
