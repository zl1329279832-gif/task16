package com.cs.alloc.service;

import com.cs.alloc.domain.QueueEntry;
import com.cs.alloc.domain.Session;
import com.cs.alloc.mapper.QueueEntryMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class QueueServiceTest {
    @Mock private QueueEntryMapper queueEntryMapper; @Mock private RedisService redisService;
    private QueueService svc;

    @BeforeEach void setUp() { svc = new QueueService(queueEntryMapper, redisService); }

    @Test @DisplayName("正常入队")
    void join() {
        when(queueEntryMapper.selectBySessionId(1L)).thenReturn(null);
        when(queueEntryMapper.countBySkillGroupId(1L)).thenReturn(2);
        QueueEntry e = svc.join(session(1L), 2);
        assertThat(e.getPosition()).isEqualTo(3);
        verify(redisService).addToQueue(eq(1L), eq(1L), anyDouble());
    }

    @Test @DisplayName("幂等入队")
    void joinIdempotent() {
        QueueEntry ex = new QueueEntry(); ex.setSessionId(1L); ex.setPosition(1);
        when(queueEntryMapper.selectBySessionId(1L)).thenReturn(ex);
        assertThat(svc.join(session(1L), 0).getSessionId()).isEqualTo(1L);
        verify(queueEntryMapper, never()).insert(any());
    }

    @Test @DisplayName("离队")
    void leave() {
        QueueEntry e = new QueueEntry(); e.setSessionId(1L); e.setSkillGroupId(1L);
        when(queueEntryMapper.selectBySessionId(1L)).thenReturn(e);
        svc.leave(1L);
        verify(queueEntryMapper).deleteBySessionId(1L);
    }

    @Test @DisplayName("离队-不在排队")
    void leaveNotInQueue() {
        when(queueEntryMapper.selectBySessionId(1L)).thenReturn(null);
        svc.leave(1L); // should not throw
    }

    @Test @DisplayName("排队位置")
    void position() {
        QueueEntry e = new QueueEntry(); e.setSessionId(1L); e.setSkillGroupId(1L);
        when(queueEntryMapper.selectBySessionId(1L)).thenReturn(e);
        when(redisService.getQueuePosition(1L, 1L)).thenReturn(2L);
        assertThat(svc.getPosition(1L)).isEqualTo(3);
    }

    @Test @DisplayName("重新入队")
    void rejoin() {
        Session s = session(1L); s.setPriorityScore(50);
        when(queueEntryMapper.countBySkillGroupId(1L)).thenReturn(0);
        svc.rejoin(s);
        verify(queueEntryMapper).deleteBySessionId(1L);
        verify(queueEntryMapper).insert(any());
    }

    private Session session(long id) { Session s = new Session(); s.setId(id); s.setSessionNo("CS" + id); s.setSkillGroupId(1L); s.setCustomerId(100L); s.setStatus("WAITING"); return s; }
}
