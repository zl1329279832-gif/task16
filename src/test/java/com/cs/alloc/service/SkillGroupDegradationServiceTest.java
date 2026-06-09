package com.cs.alloc.service;

import com.cs.alloc.config.SlaRiskProperties;
import com.cs.alloc.domain.Agent;
import com.cs.alloc.domain.AuditLog;
import com.cs.alloc.domain.QueueEntry;
import com.cs.alloc.domain.Session;
import com.cs.alloc.mapper.AgentMapper;
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
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SkillGroupDegradationServiceTest {
    @Mock private QueueEntryMapper queueEntryMapper;
    @Mock private AgentMapper agentMapper;
    @Mock private AuditLogMapper auditLogMapper;
    @Mock private RedisService redisService;
    @Mock private MessageQueue messageQueue;
    @Mock private SessionMapper sessionMapper;
    private SlaRiskProperties properties;
    private SkillGroupDegradationService service;

    @BeforeEach
    void setUp() {
        properties = new SlaRiskProperties();
        properties.setDegradationTimeoutSeconds(0); // immediate for testing
        properties.setFallbackSkillGroups(Map.of(1L, 99L));
        service = new SkillGroupDegradationService(queueEntryMapper, agentMapper, auditLogMapper,
                redisService, messageQueue, properties, sessionMapper);
    }

    @Test @DisplayName("无可用客服超过阈值触发降级 — 仅迁移WAITING会话")
    void triggerDegradation() {
        when(agentMapper.selectBySkillGroupId(1L)).thenReturn(Collections.emptyList());
        QueueEntry e1 = qe(1L, 1L);
        QueueEntry e2 = qe(2L, 1L);
        when(queueEntryMapper.selectWaitingBySkillGroupId(1L)).thenReturn(List.of(e1, e2));
        when(queueEntryMapper.batchUpdateSkillGroupWaiting(1L, 99L)).thenReturn(2);

        boolean result = service.checkAndDegrade(1L);
        assertThat(result).isTrue();
        verify(queueEntryMapper).batchUpdateSkillGroupWaiting(1L, 99L);
        verify(redisService, times(2)).removeFromQueue(eq(1L), anyLong());
        verify(redisService, times(2)).addToQueue(eq(99L), anyLong(), anyDouble());
        verify(messageQueue).publish(eq(MessageQueue.Topics.SKILLGROUP_DEGRADED), anyString());
        verify(auditLogMapper).insert(argThat(a -> "SKILL_GROUP_DEGRADE".equals(a.getAction())));
    }

    @Test @DisplayName("有可用客服不触发降级")
    void availableAgentsNoDegradation() {
        Agent agent = agent(10L, 1L);
        when(agentMapper.selectBySkillGroupId(1L)).thenReturn(List.of(agent));
        when(redisService.isAgentAvailable(10L)).thenReturn(true);
        when(redisService.isHeartbeatAlive(10L)).thenReturn(true);

        boolean result = service.checkAndDegrade(1L);
        assertThat(result).isFalse();
        verify(queueEntryMapper, never()).batchUpdateSkillGroupWaiting(anyLong(), anyLong());
    }

    @Test @DisplayName("无fallback配置跳过降级")
    void noFallbackSkip() {
        properties.setFallbackSkillGroups(Map.of());
        boolean result = service.checkAndDegrade(1L);
        assertThat(result).isFalse();
    }

    @Test @DisplayName("降级保留原始技能组ID")
    void preserveOriginalGroupId() {
        when(agentMapper.selectBySkillGroupId(1L)).thenReturn(Collections.emptyList());
        QueueEntry e1 = qe(1L, 1L);
        when(queueEntryMapper.selectWaitingBySkillGroupId(1L)).thenReturn(List.of(e1));
        when(queueEntryMapper.batchUpdateSkillGroupWaiting(1L, 99L)).thenReturn(1);

        service.checkAndDegrade(1L);
        // batchUpdateSkillGroupWaiting sets original_skill_group_id via COALESCE
        verify(queueEntryMapper).batchUpdateSkillGroupWaiting(1L, 99L);
    }

    @Test @DisplayName("恢复: 仅迁回WAITING会话")
    void restoreMigratedOnlyWaiting() {
        QueueEntry e1 = qe(1L, 99L);
        e1.setOriginalSkillGroupId(1L);
        QueueEntry e2 = qe(2L, 99L);
        e2.setOriginalSkillGroupId(1L);
        when(queueEntryMapper.selectByOriginalSkillGroupId(1L)).thenReturn(List.of(e1, e2));
        // session 1 is WAITING, session 2 is ASSIGNED
        when(sessionMapper.selectById(1L)).thenReturn(waitingSession(1L));
        Session assigned = new Session();
        assigned.setId(2L);
        assigned.setStatus("ASSIGNED");
        when(sessionMapper.selectById(2L)).thenReturn(assigned);

        boolean result = service.restore(1L);
        assertThat(result).isTrue();
        // Only session 1 (WAITING) should be restored
        verify(queueEntryMapper, times(1)).updateSkillGroupId(eq(1L), eq(1L), isNull());
        verify(redisService, times(1)).removeFromQueue(eq(99L), eq(1L));
        verify(redisService, times(1)).addToQueue(eq(1L), eq(1L), anyDouble());
        // Session 2 (ASSIGNED) should NOT be restored
        verify(queueEntryMapper, never()).updateSkillGroupId(eq(2L), anyLong(), any());
        verify(messageQueue).publish(eq(MessageQueue.Topics.SKILLGROUP_RESTORED), anyString());
        verify(auditLogMapper).insert(argThat(a -> "SKILL_GROUP_RESTORE".equals(a.getAction())));
    }

    @Test @DisplayName("恢复: 无已迁移会话返回false")
    void restoreEmpty() {
        when(queueEntryMapper.selectByOriginalSkillGroupId(1L)).thenReturn(Collections.emptyList());
        boolean result = service.restore(1L);
        assertThat(result).isFalse();
    }

    @Test @DisplayName("空WAITING队列不触发降级迁移")
    void emptyWaitingQueueNoDegradation() {
        when(agentMapper.selectBySkillGroupId(1L)).thenReturn(Collections.emptyList());
        when(queueEntryMapper.selectWaitingBySkillGroupId(1L)).thenReturn(Collections.emptyList());

        boolean result = service.checkAndDegrade(1L);
        assertThat(result).isFalse();
        verify(queueEntryMapper, never()).batchUpdateSkillGroupWaiting(anyLong(), anyLong());
    }

    // ===== 新增: 客服离线场景测试 =====

    @Test @DisplayName("客服心跳超时: 不计入可用客服, 可触发降级")
    void heartbeatTimeoutTriggersDegrade() {
        Agent agent = agent(10L, 1L);
        when(agentMapper.selectBySkillGroupId(1L)).thenReturn(List.of(agent));
        when(redisService.isAgentAvailable(10L)).thenReturn(true);
        when(redisService.isHeartbeatAlive(10L)).thenReturn(false); // heartbeat dead

        QueueEntry e1 = qe(1L, 1L);
        when(queueEntryMapper.selectWaitingBySkillGroupId(1L)).thenReturn(List.of(e1));
        when(queueEntryMapper.batchUpdateSkillGroupWaiting(1L, 99L)).thenReturn(1);

        boolean result = service.checkAndDegrade(1L);
        assertThat(result).isTrue();
    }

    @Test @DisplayName("客服在线但心跳丢失: 不计为可用")
    void onlineButNoHeartbeatNotAvailable() {
        Agent agent = agent(10L, 1L);
        when(agentMapper.selectBySkillGroupId(1L)).thenReturn(List.of(agent));
        when(redisService.isAgentAvailable(10L)).thenReturn(true);
        when(redisService.isHeartbeatAlive(10L)).thenReturn(false);

        // Should count as 0 available agents
        QueueEntry e1 = qe(1L, 1L);
        when(queueEntryMapper.selectWaitingBySkillGroupId(1L)).thenReturn(List.of(e1));
        when(queueEntryMapper.batchUpdateSkillGroupWaiting(1L, 99L)).thenReturn(1);

        boolean result = service.checkAndDegrade(1L);
        assertThat(result).isTrue();
        verify(queueEntryMapper).batchUpdateSkillGroupWaiting(1L, 99L);
    }

    private QueueEntry qe(long sid, long sg) {
        QueueEntry q = new QueueEntry();
        q.setSessionId(sid); q.setSkillGroupId(sg); q.setCustomerId(100L);
        q.setPriorityScore(0); q.setJoinedAt(LocalDateTime.now());
        return q;
    }
    private Agent agent(long id, long sg) {
        Agent a = new Agent(); a.setId(id); a.setSkillGroupId(sg); a.setMaxCapacity(5);
        a.setIsSupervisor(false); a.setStatus("ONLINE"); return a;
    }
    private Session waitingSession(long id) {
        Session s = new Session();
        s.setId(id);
        s.setStatus("WAITING");
        return s;
    }
}
