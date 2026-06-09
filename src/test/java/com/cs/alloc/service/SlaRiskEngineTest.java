package com.cs.alloc.service;

import com.cs.alloc.config.SlaRiskProperties;
import com.cs.alloc.domain.SkillGroup;
import com.cs.alloc.domain.SlaRiskScore;
import com.cs.alloc.mapper.SkillGroupMapper;
import com.cs.alloc.mapper.SlaRiskHistoryMapper;
import com.cs.alloc.ws.WsEventPusher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.LocalDateTime;
import java.util.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SlaRiskEngineTest {
    @Mock private SkillGroupMapper skillGroupMapper;
    @Mock private SlaRiskHistoryMapper slaRiskHistoryMapper;
    @Mock private SlaRiskCalculator slaRiskCalculator;
    @Mock private QueueReorderService queueReorderService;
    @Mock private SkillGroupDegradationService degradationService;
    @Mock private QueueSnapshotService snapshotService;
    @Mock private MessageQueue messageQueue;
    @Mock private WsEventPusher wsEventPusher;
    private SlaRiskProperties properties;
    private SlaRiskEngine engine;

    @BeforeEach
    void setUp() {
        properties = new SlaRiskProperties();
        properties.setSnapshotIntervalSeconds(0); // always save for tests
        engine = new SlaRiskEngine(skillGroupMapper, slaRiskHistoryMapper, slaRiskCalculator,
                queueReorderService, degradationService, snapshotService, messageQueue, wsEventPusher, properties);
        ReflectionTestUtils.setField(engine, "lastSnapshotTime", 0L);
    }

    @Test @DisplayName("处理所有活跃技能组")
    void processAllGroups() {
        SkillGroup g1 = sg(1L); SkillGroup g2 = sg(2L);
        when(skillGroupMapper.selectAllActive()).thenReturn(List.of(g1, g2));
        when(slaRiskCalculator.calculateSkillGroupRisks(anyLong())).thenReturn(Collections.emptyMap());

        engine.runSlaCycle();

        verify(slaRiskCalculator).calculateSkillGroupRisks(1L);
        verify(slaRiskCalculator).calculateSkillGroupRisks(2L);
    }

    @Test @DisplayName("高风险触发重排")
    void highRiskTriggersReorder() {
        SkillGroup g1 = sg(1L);
        when(skillGroupMapper.selectAllActive()).thenReturn(List.of(g1));
        Map<Long, SlaRiskScore> scores = Map.of(1L, risk(1L, 80.0)); // above threshold 75
        when(slaRiskCalculator.calculateSkillGroupRisks(1L)).thenReturn(scores);

        engine.runSlaCycle();

        verify(queueReorderService).reorderQueueByRisk(1L);
    }

    @Test @DisplayName("低风险不触发重排")
    void lowRiskNoReorder() {
        SkillGroup g1 = sg(1L);
        when(skillGroupMapper.selectAllActive()).thenReturn(List.of(g1));
        Map<Long, SlaRiskScore> scores = Map.of(1L, risk(1L, 50.0)); // below threshold
        when(slaRiskCalculator.calculateSkillGroupRisks(1L)).thenReturn(scores);

        engine.runSlaCycle();

        verify(queueReorderService, never()).reorderQueueByRisk(anyLong());
    }

    @Test @DisplayName("定期保存快照")
    void periodicSnapshots() {
        SkillGroup g1 = sg(1L);
        when(skillGroupMapper.selectAllActive()).thenReturn(List.of(g1));
        when(slaRiskCalculator.calculateSkillGroupRisks(anyLong())).thenReturn(Collections.emptyMap());

        engine.runSlaCycle();

        verify(snapshotService).saveSnapshot(eq(1L), any());
    }

    @Test @DisplayName("高风险推送告警")
    void highRiskPushAlert() {
        SkillGroup g1 = sg(1L);
        when(skillGroupMapper.selectAllActive()).thenReturn(List.of(g1));
        Map<Long, SlaRiskScore> scores = Map.of(1L, risk(1L, 80.0));
        when(slaRiskCalculator.calculateSkillGroupRisks(1L)).thenReturn(scores);

        engine.runSlaCycle();

        verify(messageQueue).publish(eq(MessageQueue.Topics.SLA_RISK_UPDATED), contains("true"));
    }

    @Test @DisplayName("无技能组时跳过")
    void noGroupsSkip() {
        when(skillGroupMapper.selectAllActive()).thenReturn(Collections.emptyList());

        engine.runSlaCycle();

        verify(slaRiskCalculator, never()).calculateSkillGroupRisks(anyLong());
    }

    @Test @DisplayName("风险历史记录被保存")
    void riskHistorySaved() {
        SkillGroup g1 = sg(1L);
        when(skillGroupMapper.selectAllActive()).thenReturn(List.of(g1));
        SlaRiskScore score = SlaRiskScore.builder()
                .sessionId(1L).riskScore(60.0).vipLevel(0).waitSeconds(100L)
                .availableAgents(2).avgAgentLoad(1.5).calculatedAt(System.currentTimeMillis())
                .build();
        when(slaRiskCalculator.calculateSkillGroupRisks(1L)).thenReturn(Map.of(1L, score));

        engine.runSlaCycle();

        verify(slaRiskHistoryMapper).insert(any());
    }

    private SkillGroup sg(long id) {
        SkillGroup s = new SkillGroup(); s.setId(id); s.setName("Group" + id); s.setActive(true); return s;
    }
    private SlaRiskScore risk(long sessionId, double score) {
        return SlaRiskScore.builder().sessionId(sessionId).riskScore(score)
                .vipLevel(0).waitSeconds(60L).availableAgents(2).avgAgentLoad(1.0)
                .historicalAht(300L).agentHeartbeatOk(true).calculatedAt(System.currentTimeMillis())
                .build();
    }
}
