package com.cs.alloc.service;

import com.cs.alloc.common.BizException;
import com.cs.alloc.domain.AuditLog;
import com.cs.alloc.domain.QueueEntry;
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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
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
        when(sessionMapper.selectStatus(anyLong())).thenReturn("WAITING");

        boolean result = service.reorderQueueByRisk(1L);
        assertThat(result).isTrue();
        // session 2 (highest risk) should be position 1
        verify(queueEntryMapper).updatePosition(eq(2L), eq(1));
        verify(queueEntryMapper).updatePosition(eq(1L), eq(2));
        verify(queueEntryMapper).updatePosition(eq(3L), eq(3));
        verify(auditLogMapper).insert(argThat(a -> "QUEUE_REORDER".equals(a.getAction())));
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
        when(sessionMapper.selectStatus(1L)).thenReturn("WAITING");

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
        when(sessionMapper.selectStatus(1L)).thenReturn("WAITING");

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
        when(sessionMapper.selectStatus(1L)).thenReturn("WAITING");

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
        when(sessionMapper.selectStatus(1L)).thenReturn("WAITING");

        service.reorderQueueByRisk(1L);

        verify(auditLogMapper).insert(argThat(a ->
                "QUEUE_REORDER".equals(a.getAction()) && "SYSTEM".equals(a.getOperatorType())));
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
}
