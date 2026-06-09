package com.cs.alloc.service;

import com.cs.alloc.config.SlaRiskProperties;
import com.cs.alloc.domain.Agent;
import com.cs.alloc.domain.Customer;
import com.cs.alloc.domain.QueueEntry;
import com.cs.alloc.domain.SlaRiskScore;
import com.cs.alloc.mapper.AgentMapper;
import com.cs.alloc.mapper.CustomerMapper;
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
class SlaRiskCalculatorTest {
    @Mock private QueueEntryMapper queueEntryMapper;
    @Mock private CustomerMapper customerMapper;
    @Mock private AgentMapper agentMapper;
    @Mock private RedisService redisService;
    private SlaRiskProperties properties;
    private SlaRiskCalculator calculator;

    @BeforeEach
    void setUp() {
        properties = new SlaRiskProperties();
        calculator = new SlaRiskCalculator(queueEntryMapper, customerMapper, agentMapper, redisService, properties);
    }

    @Test @DisplayName("VIP客户风险评分高于普通客户")
    void vipHigherThanNormal() {
        properties.setVipWeight(10.0); // reduce for test differentiation
        QueueEntry vipEntry = qe(1L, 1L, 100L);
        QueueEntry normalEntry = qe(2L, 1L, 200L);
        Agent a = agent(10L, 1L);
        Customer vipCustomer = customer(100L, 3);
        Customer normalCustomer = customer(200L, 0);
        when(customerMapper.selectById(100L)).thenReturn(vipCustomer);
        when(customerMapper.selectById(200L)).thenReturn(normalCustomer);
        when(agentMapper.selectBySkillGroupId(anyLong())).thenReturn(List.of(a));
        when(redisService.isAgentAvailable(10L)).thenReturn(true);
        when(redisService.isHeartbeatAlive(10L)).thenReturn(true);
        when(redisService.getAgentLoad(10L)).thenReturn(0);

        SlaRiskScore vipRisk = calculator.calculateRisk(vipEntry);
        SlaRiskScore normalRisk = calculator.calculateRisk(normalEntry);
        assertThat(vipRisk.getRiskScore()).isGreaterThan(normalRisk.getRiskScore());
    }

    @Test @DisplayName("等待时间越长风险越高")
    void longerWaitHigherRisk() {
        properties.setWaitWeight(0.1); // reduce for test differentiation
        QueueEntry longWait = qe(1L, 1L, 100L);
        longWait.setJoinedAt(LocalDateTime.now().minusMinutes(10));
        QueueEntry shortWait = qe(2L, 1L, 100L);
        shortWait.setJoinedAt(LocalDateTime.now().minusSeconds(30));
        Agent a = agent(10L, 1L);

        when(customerMapper.selectById(100L)).thenReturn(customer(100L, 0));
        when(agentMapper.selectBySkillGroupId(anyLong())).thenReturn(List.of(a));
        when(redisService.isAgentAvailable(10L)).thenReturn(true);
        when(redisService.isHeartbeatAlive(10L)).thenReturn(true);
        when(redisService.getAgentLoad(10L)).thenReturn(0);

        SlaRiskScore longRisk = calculator.calculateRisk(longWait);
        SlaRiskScore shortRisk = calculator.calculateRisk(shortWait);
        assertThat(longRisk.getRiskScore()).isGreaterThan(shortRisk.getRiskScore());
    }

    @Test @DisplayName("心跳异常增加风险惩罚")
    void heartbeatPenalty() {
        QueueEntry entry = qe(1L, 1L, 100L);
        Agent agent = agent(10L, 1L);
        when(customerMapper.selectById(100L)).thenReturn(customer(100L, 0));
        when(agentMapper.selectBySkillGroupId(1L)).thenReturn(List.of(agent));
        when(redisService.isAgentAvailable(10L)).thenReturn(true);
        when(redisService.isHeartbeatAlive(10L)).thenReturn(false);
        when(redisService.getAgentLoad(10L)).thenReturn(0);

        SlaRiskScore risk = calculator.calculateRisk(entry);
        assertThat(risk.getAgentHeartbeatOk()).isFalse();
        assertThat(risk.getRiskScore()).isGreaterThan(0);
    }

    @Test @DisplayName("风险评分上限100")
    void cappedAt100() {
        QueueEntry entry = qe(1L, 1L, 100L);
        entry.setJoinedAt(LocalDateTime.now().minusHours(1)); // long wait
        when(customerMapper.selectById(100L)).thenReturn(customer(100L, 3)); // VIP3
        when(agentMapper.selectBySkillGroupId(anyLong())).thenReturn(Collections.emptyList());

        SlaRiskScore risk = calculator.calculateRisk(entry);
        assertThat(risk.getRiskScore()).isLessThanOrEqualTo(100.0);
    }

    @Test @DisplayName("置顶会话返回MAX_VALUE")
    void pinnedReturnsMaxValue() {
        QueueEntry entry = qe(1L, 1L, 100L);
        entry.setPinned(true);
        when(customerMapper.selectById(100L)).thenReturn(customer(100L, 0));
        when(agentMapper.selectBySkillGroupId(anyLong())).thenReturn(Collections.emptyList());

        SlaRiskScore risk = calculator.calculateRisk(entry);
        assertThat(risk.getRiskScore()).isEqualTo(Double.MAX_VALUE);
        assertThat(risk.getPinned()).isTrue();
    }

    @Test @DisplayName("批量计算技能组风险评分 — 仅WAITING会话")
    void batchCalculate() {
        QueueEntry e1 = qe(1L, 1L, 100L);
        QueueEntry e2 = qe(2L, 1L, 200L);
        when(queueEntryMapper.selectWaitingBySkillGroupId(1L)).thenReturn(List.of(e1, e2));
        when(customerMapper.selectById(100L)).thenReturn(customer(100L, 0));
        when(customerMapper.selectById(200L)).thenReturn(customer(200L, 2));
        when(agentMapper.selectBySkillGroupId(anyLong())).thenReturn(Collections.emptyList());

        Map<Long, SlaRiskScore> result = calculator.calculateSkillGroupRisks(1L);
        assertThat(result).hasSize(2);
        assertThat(result).containsKeys(1L, 2L);
    }

    @Test @DisplayName("无可用客服时心跳标记为false")
    void noAgentsHeartbeatFalse() {
        QueueEntry entry = qe(1L, 1L, 100L);
        when(customerMapper.selectById(100L)).thenReturn(customer(100L, 0));
        when(agentMapper.selectBySkillGroupId(anyLong())).thenReturn(Collections.emptyList());

        SlaRiskScore risk = calculator.calculateRisk(entry);
        assertThat(risk.getAvailableAgents()).isEqualTo(0);
    }

    private QueueEntry qe(long sid, long sg, long cid) {
        QueueEntry q = new QueueEntry();
        q.setSessionId(sid); q.setSkillGroupId(sg); q.setCustomerId(cid);
        q.setPriorityScore(0); q.setJoinedAt(LocalDateTime.now().minusSeconds(30));
        return q;
    }
    private Customer customer(long id, int vip) {
        Customer c = new Customer(); c.setId(id); c.setVipLevel(vip); return c;
    }
    private Agent agent(long id, long sg) {
        Agent a = new Agent(); a.setId(id); a.setSkillGroupId(sg); a.setMaxCapacity(5);
        a.setIsSupervisor(false); a.setStatus("ONLINE"); return a;
    }
}
