package com.cs.alloc.service;

import com.cs.alloc.domain.QueueEntry;
import com.cs.alloc.domain.Session;
import com.cs.alloc.mapper.CustomerMapper;
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
    @Mock private CustomerMapper customerMapper;
    private QueueService svc;

    @BeforeEach void setUp() { svc = new QueueService(queueEntryMapper, redisService, customerMapper); }

    @Test @DisplayName("正常入队")
    void join() {
        when(redisService.tryLock(anyString(), any())).thenReturn("owner1");
        when(queueEntryMapper.selectBySessionId(1L)).thenReturn(null);
        when(queueEntryMapper.countBySkillGroupId(1L)).thenReturn(2);
        when(queueEntryMapper.insertIgnore(any())).thenReturn(1);
        QueueEntry e = svc.join(session(1L), 2);
        assertThat(e.getPosition()).isEqualTo(3);
        verify(redisService).addToQueue(eq(1L), eq(1L), anyDouble());
    }

    @Test @DisplayName("幂等入队")
    void joinIdempotent() {
        QueueEntry ex = new QueueEntry(); ex.setSessionId(1L); ex.setPosition(1);
        when(redisService.tryLock(anyString(), any())).thenReturn("owner1");
        when(queueEntryMapper.selectBySessionId(1L)).thenReturn(ex);
        assertThat(svc.join(session(1L), 0).getSessionId()).isEqualTo(1L);
        verify(queueEntryMapper, never()).insertIgnore(any());
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

    // ========== 新增: 并发入队测试 ==========

    @Test @DisplayName("并发入队: insertIgnore冲突返回已有记录")
    void concurrentJoinInsertIgnoreFallback() {
        when(redisService.tryLock(anyString(), any())).thenReturn("owner1");
        when(queueEntryMapper.selectBySessionId(1L))
                .thenReturn(null)     // 锁内第一次检查: 不存在
                .thenReturn(qe(1L));  // insertIgnore 失败后的查询: 已存在
        when(queueEntryMapper.countBySkillGroupId(1L)).thenReturn(0);
        when(queueEntryMapper.insertIgnore(any())).thenReturn(0); // DB唯一键冲突
        QueueEntry result = svc.join(session(1L), 0);
        assertThat(result.getSessionId()).isEqualTo(1L);
        verify(redisService, never()).addToQueue(anyLong(), anyLong(), anyDouble()); // 不应操作Redis
    }

    @Test @DisplayName("重复入队不改变排队序号")
    void duplicateJoinPreservesPosition() {
        QueueEntry existing = new QueueEntry();
        existing.setSessionId(1L); existing.setPosition(3); existing.setPriorityScore(50);
        when(redisService.tryLock(anyString(), any())).thenReturn("owner1");
        when(queueEntryMapper.selectBySessionId(1L)).thenReturn(existing);
        QueueEntry result = svc.join(session(1L), 0);
        assertThat(result.getPosition()).isEqualTo(3); // 位置不变
        assertThat(result.getPriorityScore()).isEqualTo(50); // 分数不变
        verify(queueEntryMapper, never()).insertIgnore(any()); // 不尝试插入
    }

    @Test @DisplayName("入队锁获取失败: 等待后查询已有记录")
    void joinLockFailButEntryExists() {
        when(redisService.tryLock(anyString(), any())).thenReturn(null); // 锁被占用
        when(queueEntryMapper.selectBySessionId(1L)).thenReturn(qe(1L)); // 已有记录
        QueueEntry result = svc.join(session(1L), 0);
        assertThat(result.getSessionId()).isEqualTo(1L);
    }

    private QueueEntry qe(long sid) {
        QueueEntry q = new QueueEntry(); q.setSessionId(sid); q.setPosition(1); return q;
    }
    private Session session(long id) {
        Session s = new Session(); s.setId(id); s.setSessionNo("CS" + id); s.setSkillGroupId(1L);
        s.setCustomerId(100L); s.setStatus("WAITING"); return s;
    }
}
