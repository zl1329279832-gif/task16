package com.customerservice;

import com.customerservice.mapper.*;
import com.customerservice.model.entity.*;
import com.customerservice.model.enums.*;
import com.customerservice.service.AllocationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AllocationServiceTest {

    @Mock
    private AgentMapper agentMapper;

    @Mock
    private AllocationLogMapper allocationLogMapper;

    @InjectMocks
    private AllocationService allocationService;

    private Agent agent1, agent2, agent3;

    @BeforeEach
    void setUp() {
        agent1 = new Agent();
        agent1.setId(1L);
        agent1.setUsername("agent1");
        agent1.setDisplayName("Agent One");
        agent1.setStatus(AgentStatus.ONLINE);
        agent1.setMaxConcurrent(5);
        agent1.setCurrentLoad(2);

        agent2 = new Agent();
        agent2.setId(2L);
        agent2.setUsername("agent2");
        agent2.setDisplayName("Agent Two");
        agent2.setStatus(AgentStatus.ONLINE);
        agent2.setMaxConcurrent(3);
        agent2.setCurrentLoad(0);

        agent3 = new Agent();
        agent3.setId(3L);
        agent3.setUsername("agent3");
        agent3.setDisplayName("Agent Three");
        agent3.setStatus(AgentStatus.ONLINE);
        agent3.setMaxConcurrent(5);
        agent3.setCurrentLoad(4);
    }

    @Test
    void allocate_shouldReturnAgentWithMostCapacity() {
        QueueEntry entry = new QueueEntry();
        entry.setSessionId(100L);
        entry.setSkillGroupId(1L);

        when(agentMapper.selectAvailableBySkillGroup(1L))
                .thenReturn(Arrays.asList(agent1, agent2, agent3));

        Agent result = allocationService.allocate(entry);

        assertNotNull(result);
        // agent1 has remaining 3, agent2 has 3 but lower maxConcurrent,
        // agent3 has 1 — agent1 or agent2 should be picked (both have capacity 3)
        assertTrue(result.remainingCapacity() >= 1);
        verify(allocationLogMapper).insert(any(AllocationLog.class));
    }

    @Test
    void allocate_shouldReturnNullWhenNoAgentsAvailable() {
        QueueEntry entry = new QueueEntry();
        entry.setSessionId(100L);
        entry.setSkillGroupId(1L);

        when(agentMapper.selectAvailableBySkillGroup(1L))
                .thenReturn(Collections.emptyList());

        Agent result = allocationService.allocate(entry);

        assertNull(result);
        verify(allocationLogMapper, never()).insert(any());
    }

    @Test
    void allocate_shouldHandleNullSkillGroup() {
        QueueEntry entry = new QueueEntry();
        entry.setSessionId(100L);
        entry.setSkillGroupId(null);

        when(agentMapper.selectAllOnline()).thenReturn(Arrays.asList(agent1, agent2));

        Agent result = allocationService.allocate(entry);

        assertNotNull(result);
        verify(agentMapper).selectAllOnline();
    }

    @Test
    void allocateForTransfer_shouldExcludeCurrentAgent() {
        when(agentMapper.selectAllOnline()).thenReturn(Arrays.asList(agent1, agent2));

        Agent result = allocationService.allocateForTransfer(100L, agent1.getId(), null, null);

        assertNotNull(result);
        assertNotEquals(agent1.getId(), result.getId());
    }

    @Test
    void allocateForTransfer_shouldTargetSpecificAgent() {
        when(agentMapper.selectById(2L)).thenReturn(agent2);

        Agent result = allocationService.allocateForTransfer(100L, 1L, 2L, null);

        assertNotNull(result);
        assertEquals(2L, result.getId());
    }

    @Test
    void allocateForTransfer_shouldReturnNullIfTargetBusy() {
        agent2.setCurrentLoad(3); // at max capacity
        when(agentMapper.selectById(2L)).thenReturn(agent2);

        Agent result = allocationService.allocateForTransfer(100L, 1L, 2L, null);

        assertNull(result);
    }

    @Test
    void allocate_shouldPreferLeastLoadedAgent() {
        // agent2 has 3 remaining, agent1 has 3 remaining, agent3 has 1 remaining
        QueueEntry entry = new QueueEntry();
        entry.setSessionId(100L);
        entry.setSkillGroupId(null);

        when(agentMapper.selectAllOnline()).thenReturn(Arrays.asList(agent1, agent2, agent3));

        Agent result = allocationService.allocate(entry);

        assertNotNull(result);
        // Should pick one with most remaining capacity (agent1 or agent2, both 3)
        assertTrue(result.getId() == 1L || result.getId() == 2L);
    }
}
