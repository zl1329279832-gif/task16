package com.cs.alloc.service;

import com.cs.alloc.common.BizException;
import com.cs.alloc.domain.*;
import com.cs.alloc.mapper.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConcurrencyTest {

    @Mock private SessionMapper sessionMapper;
    @Mock private CustomerMapper customerMapper;
    @Mock private AgentMapper agentMapper;
    @Mock private AgentStateMapper agentStateMapper;
    @Mock private AllocationLogMapper allocationLogMapper;
    @Mock private AuditLogMapper auditLogMapper;
    @Mock private QueueEntryMapper queueEntryMapper;
    @Mock private MessageMapper messageMapper;
    @Mock private RedisService redisService;
    @Mock private MessageQueue messageQueue;

    private SessionService sessionService;
    @Mock private QueueService queueService;
    private MessageService messageService;
    private AllocationEngine allocationEngine;

    @BeforeEach
    void setUp() {
        sessionService = new SessionService(sessionMapper, customerMapper, agentMapper, agentStateMapper,
                allocationLogMapper, auditLogMapper, queueService, redisService, messageQueue);
        messageService = new MessageService(messageMapper, sessionMapper, redisService, messageQueue);
        allocationEngine = new AllocationEngine(queueService, agentMapper, agentStateMapper,
                sessionMapper, allocationLogMapper, redisService, messageQueue);
        ReflectionTestUtils.setField(allocationEngine, "skillMatchBonus", 50);
        ReflectionTestUtils.setField(allocationEngine, "loadPenalty", 10);
    }

    // --- Bug 1: acceptSession 锁内双重校验 ---

    @Test
    @DisplayName("并发接入: 锁内重新读取到ASSIGNED状态时拒绝第二个客服")
    void acceptSession_lockRecheck_preventsDoubleAssign() {
        Session waitingSession = session(1L, "WAITING", 100L, null);
        Session assignedSession = session(1L, "ASSIGNED", 100L, 10L);

        when(sessionMapper.selectById(1L))
                .thenReturn(waitingSession)      // 锁外首次读取
                .thenReturn(assignedSession);     // 锁内重新读取 - 已被其他客服接入
        when(agentMapper.selectById(20L)).thenReturn(agent(20L, false));
        when(redisService.hasCapacity(20L)).thenReturn(true);
        when(redisService.tryLock(eq("session:1"), any(Duration.class))).thenReturn(true);

        assertThatThrownBy(() -> sessionService.acceptSession(1L, 20L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已被其他客服接入");

        verify(sessionMapper, never()).assignAgent(anyLong(), anyLong(), anyString());
    }

    // --- Bug 2: createSession 并发创建 ---

    @Test
    @DisplayName("并发创建会话: 分布式锁拦截重复请求")
    void createSession_concurrentDuplicate_blocked() {
        when(redisService.tryLock(eq("customer:create:100"), any(Duration.class))).thenReturn(false);

        assertThatThrownBy(() -> sessionService.createSession(100L, 1L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("请勿重复提交");
    }

    @Test
    @DisplayName("创建会话: MySQL检查兜底拦截并发创建")
    void createSession_mysqlFallback_rejectsExisting() {
        when(redisService.tryLock(eq("customer:create:100"), any(Duration.class))).thenReturn(true);
        when(redisService.getCustomerSession(100L)).thenReturn(Optional.empty());
        when(sessionMapper.countNonClosedByCustomerId(100L)).thenReturn(1);

        assertThatThrownBy(() -> sessionService.createSession(100L, 1L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已有进行中的会话");
    }

    // --- Bug 3: transferSession 转接回滚 ---

    @Test
    @DisplayName("转接: 目标客服不在线时拒绝且不解绑原客服")
    void transfer_targetOffline_rollback() {
        when(sessionMapper.selectById(1L)).thenReturn(session(1L, "ACTIVE", 100L, 10L));
        when(redisService.tryLock(anyString(), any(Duration.class))).thenReturn(true);
        when(agentMapper.selectById(20L)).thenReturn(agent(20L, false));
        when(redisService.isAgentAvailable(20L)).thenReturn(false);

        assertThatThrownBy(() -> sessionService.transferSession(1L, 10L, 20L, null, "test"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不在线");

        verify(redisService, never()).unbindSessionFromAgent(anyLong(), anyLong());
        verify(redisService, never()).decrementAgentLoad(anyLong());
    }

    @Test
    @DisplayName("转接: 目标客服心跳过期时拒绝")
    void transfer_targetHeartbeatExpired_rejected() {
        when(sessionMapper.selectById(1L)).thenReturn(session(1L, "ACTIVE", 100L, 10L));
        when(redisService.tryLock(anyString(), any(Duration.class))).thenReturn(true);
        when(agentMapper.selectById(20L)).thenReturn(agent(20L, false));
        when(redisService.isAgentAvailable(20L)).thenReturn(true);
        when(redisService.isHeartbeatAlive(20L)).thenReturn(false);

        assertThatThrownBy(() -> sessionService.transferSession(1L, 10L, 20L, null, "test"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("心跳超时");

        verify(redisService, never()).unbindSessionFromAgent(anyLong(), anyLong());
    }

    // --- Bug 5: 消息幂等 Redis过期MySQL兜底 ---

    @Test
    @DisplayName("消息幂等: Redis过期后MySQL DuplicateKey降级返回已有消息")
    void message_redisExpired_mysqlDuplicate_idempotent() {
        Session activeSession = session(1L, "ACTIVE", 100L, 10L);
        when(sessionMapper.selectById(1L)).thenReturn(activeSession);
        when(redisService.trySetIdempotencyKey(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        doThrow(new DuplicateKeyException("Duplicate entry")).when(messageMapper).insert(any());
        Message existing = new Message();
        existing.setId(999L);
        existing.setContent("hi");
        when(messageMapper.selectByIdempotencyKey("dup-key")).thenReturn(existing);

        Message result = messageService.sendMessage(1L, "100", "CUSTOMER", "hi", "TEXT", "dup-key");
        assertThat(result.getId()).isEqualTo(999L);
        verify(messageMapper).insert(any());
    }

    // --- Bug 7: agentOffline 带锁释放 ---

    @Test
    @DisplayName("客服离线: 每个会话独立加锁, 锁失败跳过")
    void agentOffline_perSessionLock() {
        Session s1 = session(1L, "ACTIVE", 100L, 10L);
        Session s2 = session(2L, "ACTIVE", 101L, 10L);
        Session s2current = session(2L, "ACTIVE", 101L, 10L);

        when(sessionMapper.selectByAgentIdAndStatus(10L, "ACTIVE")).thenReturn(new ArrayList<>(List.of(s1, s2)));
        when(sessionMapper.selectByAgentIdAndStatus(10L, "ASSIGNED")).thenReturn(new ArrayList<>());
        when(redisService.tryLock(eq("session:1"), any(Duration.class))).thenReturn(false);
        when(redisService.tryLock(eq("session:2"), any(Duration.class))).thenReturn(true);
        when(sessionMapper.selectById(2L)).thenReturn(s2current);

        sessionService.agentOffline(10L);

        verify(sessionMapper, never()).updateStatus(eq(1L), anyString());
        verify(sessionMapper).updateStatus(2L, "WAITING");
        verify(queueService).rejoin(s2);
    }

    // --- Bug 9: 并发入队幂等 ---

    @Test
    @DisplayName("并发入队: MySQL DuplicateKey降级返回已有条目")
    void queueJoin_concurrent_idempotent() {
        QueueService realQueueService = new QueueService(queueEntryMapper, redisService);
        Session s = session(1L, "WAITING", 100L, null);
        when(queueEntryMapper.selectBySessionId(1L)).thenReturn(null);
        when(queueEntryMapper.countBySkillGroupId(1L)).thenReturn(0);
        doThrow(new DuplicateKeyException("Duplicate entry")).when(queueEntryMapper).insert(any());

        QueueEntry existing = new QueueEntry();
        existing.setSessionId(1L);
        existing.setPosition(1);
        when(queueEntryMapper.selectBySessionId(1L)).thenReturn(null).thenReturn(existing);

        QueueEntry result = realQueueService.join(s, 0);
        assertThat(result.getSessionId()).isEqualTo(1L);
        verify(redisService, never()).addToQueue(anyLong(), anyLong(), anyDouble());
    }

    // --- Bug 4: 分配引擎心跳检查 ---

    @Test
    @DisplayName("分配引擎: 跳过心跳过期的客服")
    void allocation_skipsExpiredHeartbeat() {
        Agent a1 = agent(1L, false);
        a1.setSkillGroupId(1L);
        a1.setMaxCapacity(5);
        Agent a2 = agent(2L, false);
        a2.setSkillGroupId(1L);
        a2.setMaxCapacity(5);

        QueueEntry qe = new QueueEntry();
        qe.setSessionId(100L);
        qe.setSkillGroupId(1L);
        qe.setCustomerId(200L);
        qe.setPriorityScore(0);
        qe.setJoinedAt(LocalDateTime.now());

        when(queueService.getAllWaiting()).thenReturn(List.of(qe));
        when(agentMapper.selectBySkillGroupId(1L)).thenReturn(List.of(a1, a2));
        when(redisService.isAgentAvailable(1L)).thenReturn(true);
        when(redisService.isAgentAvailable(2L)).thenReturn(true);
        when(redisService.isHeartbeatAlive(1L)).thenReturn(false);
        when(redisService.isHeartbeatAlive(2L)).thenReturn(true);
        when(redisService.hasCapacity(2L)).thenReturn(true);
        AgentState state2 = new AgentState();
        state2.setAgentId(2L);
        state2.setCurrentLoad(0);
        when(agentStateMapper.selectByAgentId(2L)).thenReturn(state2);
        when(redisService.tryLock(anyString(), any(Duration.class))).thenReturn(true);

        Session waitingSession = session(100L, "WAITING", 200L, null);
        waitingSession.setSessionNo("CS100");
        when(sessionMapper.selectById(100L)).thenReturn(waitingSession);
        when(redisService.getAgentLoad(2L)).thenReturn(1);

        allocationEngine.allocateRound();

        verify(sessionMapper).assignAgent(100L, 2L, "ASSIGNED");
        verify(sessionMapper, never()).assignAgent(eq(100L), eq(1L), anyString());
    }

    // --- helpers ---

    private Session session(long id, String status, long cid, Long aid) {
        Session s = new Session();
        s.setId(id);
        s.setSessionNo("CS" + id);
        s.setCustomerId(cid);
        s.setAgentId(aid);
        s.setSkillGroupId(1L);
        s.setStatus(status);
        return s;
    }

    private Agent agent(long id, boolean supervisor) {
        Agent a = new Agent();
        a.setId(id);
        a.setSkillGroupId(1L);
        a.setMaxCapacity(5);
        a.setIsSupervisor(supervisor);
        a.setStatus("ONLINE");
        return a;
    }
}
