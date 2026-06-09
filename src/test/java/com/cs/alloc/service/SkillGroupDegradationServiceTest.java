package com.cs.alloc.service;

import com.cs.alloc.config.SlaRiskProperties;
import com.cs.alloc.domain.Agent;
import com.cs.alloc.domain.AuditLog;
import com.cs.alloc.domain.QueueEntry;
import com.cs.alloc.mapper.AgentMapper;
import com.cs.alloc.mapper.AuditLogMapper;
import com.cs.alloc.mapper.QueueEntryMapper;
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
    private SlaRiskProperties properties;
    private SkillGroupDegradationService service;

    @BeforeEach
    void setUp() {
        properties = new SlaRiskProperties();
        properties.setDegradationTimeoutSeconds(0); // immediate for testing
        properties.setFallbackSkillGroups(Map.of(1L, 99L));
        service = new SkillGroupDegradationService(queueEntryMapper, agentMapper, auditLogMapper,
                redisService, messageQueue, properties);
    }

    @Test @DisplayName("无可用客服超过阈值触发降级")
    void triggerDegradation() {
        when(agentMapper.selectBySkillGroupId(1L)).thenReturn(Collections.emptyList());
        QueueEntry e1 = qe(1L, 1L);
        QueueEntry e2 = qe(2L, 1L);
        when(queueEntryMapper.selectBySkillGroupId(1L)).thenReturn(List.of(e1, e2));
        when(queueEntryMapper.batchUpdateSkillGroup(1L, 99L)).thenReturn(2);

        boolean result = service.checkAndDegrade(1L);
        assertThat(result).isTrue();
        verify(queueEntryMapper).batchUpdateSkillGroup(1L, 99L);
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
        verify(queueEntryMapper, never()).batchUpdateSkillGroup(anyLong(), anyLong());
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
        when(queueEntryMapper.selectBySkillGroupId(1L)).thenReturn(List.of(e1));
        when(queueEntryMapper.batchUpdateSkillGroup(1L, 99L)).thenReturn(1);

        service.checkAndDegrade(1L);
        // batchUpdateSkillGroup sets original_skill_group_id = COALESCE(original_skill_group_id, skill_group_id)
        verify(queueEntryMapper).batchUpdateSkillGroup(1L, 99L);
    }

    @Test @DisplayName("恢复: 迁回原技能组")
    void restoreMigrated() {
        QueueEntry e1 = qe(1L, 99L);
        e1.setOriginalSkillGroupId(1L);
        QueueEntry e2 = qe(2L, 99L);
        e2.setOriginalSkillGroupId(1L);
        when(queueEntryMapper.selectByOriginalSkillGroupId(1L)).thenReturn(List.of(e1, e2));

        boolean result = service.restore(1L);
        assertThat(result).isTrue();
        verify(queueEntryMapper, times(2)).updateSkillGroupId(anyLong(), eq(1L), isNull());
        verify(redisService, times(2)).removeFromQueue(eq(99L), anyLong());
        verify(redisService, times(2)).addToQueue(eq(1L), anyLong(), anyDouble());
        verify(messageQueue).publish(eq(MessageQueue.Topics.SKILLGROUP_RESTORED), anyString());
        verify(auditLogMapper).insert(argThat(a -> "SKILL_GROUP_RESTORE".equals(a.getAction())));
    }

    @Test @DisplayName("恢复: 无已迁移会话返回false")
    void restoreEmpty() {
        when(queueEntryMapper.selectByOriginalSkillGroupId(1L)).thenReturn(Collections.emptyList());
        boolean result = service.restore(1L);
        assertThat(result).isFalse();
    }

    @Test @DisplayName("空队列不触发降级迁移")
    void emptyQueueNoDegradation() {
        when(agentMapper.selectBySkillGroupId(1L)).thenReturn(Collections.emptyList());
        when(queueEntryMapper.selectBySkillGroupId(1L)).thenReturn(Collections.emptyList());

        boolean result = service.checkAndDegrade(1L);
        assertThat(result).isFalse();
        verify(queueEntryMapper, never()).batchUpdateSkillGroup(anyLong(), anyLong());
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
}
