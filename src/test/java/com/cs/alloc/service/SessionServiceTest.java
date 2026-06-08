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
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SessionServiceTest {
    @Mock private SessionMapper sessionMapper; @Mock private CustomerMapper customerMapper;
    @Mock private AgentMapper agentMapper; @Mock private AgentStateMapper agentStateMapper;
    @Mock private AllocationLogMapper allocationLogMapper; @Mock private AuditLogMapper auditLogMapper;
    @Mock private QueueService queueService; @Mock private RedisService redisService; @Mock private MessageQueue messageQueue;
    private SessionService svc;

    @BeforeEach void setUp() {
        svc = new SessionService(sessionMapper, customerMapper, agentMapper, agentStateMapper, allocationLogMapper, auditLogMapper, queueService, redisService, messageQueue);
    }

    @Test @DisplayName("创建会话: 正常入队")
    void createSession() {
        when(redisService.getCustomerSession(1L)).thenReturn(java.util.Optional.empty());
        when(customerMapper.selectById(1L)).thenReturn(customer(1L, 2));
        Session s = svc.createSession(1L, 1L);
        assertThat(s.getStatus()).isEqualTo("WAITING");
        verify(queueService).join(any(), eq(2));
    }

    @Test @DisplayName("创建会话: 已有活跃会话拒绝")
    void createSessionReject() {
        when(redisService.getCustomerSession(1L)).thenReturn(java.util.Optional.of(999L));
        Session ex = new Session(); ex.setId(999L); ex.setStatus("ACTIVE"); ex.setSessionNo("CS123");
        when(sessionMapper.selectById(999L)).thenReturn(ex);
        assertThatThrownBy(() -> svc.createSession(1L, 1L)).isInstanceOf(BizException.class);
    }

    @Test @DisplayName("接入: 正常分配")
    void acceptSession() {
        when(sessionMapper.selectById(1L))
            .thenReturn(session(1L, "WAITING", 100L, null))
            .thenReturn(session(1L, "ASSIGNED", 100L, 10L));
        when(agentMapper.selectById(10L)).thenReturn(agent(10L, false));
        when(redisService.hasCapacity(10L)).thenReturn(true);
        when(redisService.tryLock(anyString(), any())).thenReturn(true);
        when(redisService.getAgentLoad(10L)).thenReturn(1);
        svc.acceptSession(1L, 10L);
        verify(sessionMapper).assignAgent(1L, 10L, "ASSIGNED");
    }

    @Test @DisplayName("接入: 非WAITING拒绝")
    void acceptReject() {
        when(sessionMapper.selectById(1L)).thenReturn(session(1L, "ACTIVE", 100L, 10L));
        assertThatThrownBy(() -> svc.acceptSession(1L, 20L)).isInstanceOf(BizException.class);
    }

    @Test @DisplayName("接入: 满载拒绝")
    void acceptFull() {
        when(sessionMapper.selectById(1L)).thenReturn(session(1L, "WAITING", 100L, null));
        when(agentMapper.selectById(10L)).thenReturn(agent(10L, false));
        when(redisService.hasCapacity(10L)).thenReturn(false);
        assertThatThrownBy(() -> svc.acceptSession(1L, 10L)).isInstanceOf(BizException.class);
    }

    @Test @DisplayName("转接: 指定目标")
    void transferToAgent() {
        when(sessionMapper.selectById(1L)).thenReturn(session(1L, "ACTIVE", 100L, 10L));
        when(agentMapper.selectById(20L)).thenReturn(agent(20L, false));
        when(redisService.hasCapacity(20L)).thenReturn(true);
        when(redisService.tryLock(anyString(), any())).thenReturn(true);
        when(redisService.getAgentLoad(anyLong())).thenReturn(1);
        svc.transferSession(1L, 10L, 20L, null, "测试");
        verify(redisService).unbindSessionFromAgent(1L, 10L);
        verify(sessionMapper).assignAgent(1L, 20L, "ASSIGNED");
    }

    @Test @DisplayName("转接: 退回排队")
    void transferToQueue() {
        when(sessionMapper.selectById(1L)).thenReturn(session(1L, "ACTIVE", 100L, 10L));
        when(redisService.tryLock(anyString(), any())).thenReturn(true);
        when(redisService.getAgentLoad(anyLong())).thenReturn(0);
        svc.transferSession(1L, 10L, null, 2L, "测试");
        verify(queueService).rejoin(any());
    }

    @Test @DisplayName("主管接管: 非主管拒绝")
    void takeoverReject() {
        when(sessionMapper.selectById(1L)).thenReturn(session(1L, "ACTIVE", 100L, 10L));
        when(agentMapper.selectById(99L)).thenReturn(agent(99L, false));
        assertThatThrownBy(() -> svc.supervisorTakeover(1L, 99L)).isInstanceOf(BizException.class).hasMessageContaining("仅主管");
    }

    @Test @DisplayName("主管接管: 正常")
    void takeoverOk() {
        when(sessionMapper.selectById(1L)).thenReturn(session(1L, "ACTIVE", 100L, 10L));
        when(agentMapper.selectById(4L)).thenReturn(agent(4L, true));
        when(redisService.tryLock(anyString(), any())).thenReturn(true);
        when(redisService.getAgentLoad(anyLong())).thenReturn(1);
        svc.supervisorTakeover(1L, 4L);
        verify(allocationLogMapper).insert(argThat(l -> "TAKEOVER".equals(l.getAction())));
    }

    @Test @DisplayName("关闭会话")
    void closeSession() {
        when(sessionMapper.selectById(1L)).thenReturn(session(1L, "ACTIVE", 100L, 10L));
        when(redisService.tryLock(anyString(), any())).thenReturn(true);
        when(redisService.getAgentLoad(10L)).thenReturn(0);
        svc.closeSession(1L, "10", "AGENT", "完成");
        verify(sessionMapper).close(1L);
    }

    private Customer customer(long id, int vip) { Customer c = new Customer(); c.setId(id); c.setVipLevel(vip); return c; }
    private Agent agent(long id, boolean supervisor) { Agent a = new Agent(); a.setId(id); a.setSkillGroupId(1L); a.setMaxCapacity(5); a.setIsSupervisor(supervisor); a.setStatus("ONLINE"); return a; }
    private Session session(long id, String status, long cid, Long aid) { Session s = new Session(); s.setId(id); s.setSessionNo("CS" + id); s.setCustomerId(cid); s.setAgentId(aid); s.setSkillGroupId(1L); s.setStatus(status); return s; }
}
