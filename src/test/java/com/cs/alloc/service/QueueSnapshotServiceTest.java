package com.cs.alloc.service;

import com.cs.alloc.domain.QueueEntry;
import com.cs.alloc.domain.QueueSnapshot;
import com.cs.alloc.domain.SlaRiskScore;
import com.cs.alloc.mapper.QueueEntryMapper;
import com.cs.alloc.mapper.SessionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class QueueSnapshotServiceTest {
    @Mock private QueueEntryMapper queueEntryMapper;
    @Mock private RedisService redisService;
    @Mock private SlaRiskCalculator slaRiskCalculator;
    @Mock private SessionMapper sessionMapper;
    private QueueSnapshotService service;

    @BeforeEach
    void setUp() {
        service = new QueueSnapshotService(queueEntryMapper, redisService, slaRiskCalculator, sessionMapper);
    }

    @Test @DisplayName("保存快照到Redis")
    void saveSnapshot() {
        QueueEntry e1 = qe(1L, 1L);
        when(queueEntryMapper.selectBySkillGroupId(1L)).thenReturn(List.of(e1));
        when(sessionMapper.selectStatus(1L)).thenReturn("WAITING");
        when(slaRiskCalculator.calculateSkillGroupRisks(1L)).thenReturn(Map.of(1L, risk(1L, 50.0)));

        QueueSnapshot snapshot = service.saveSnapshot(1L, Duration.ofSeconds(300));

        assertThat(snapshot.getTotalEntries()).isEqualTo(1);
        assertThat(snapshot.getAvgRiskScore()).isEqualTo(50.0);
        verify(redisService).saveQueueSnapshot(eq(1L), anyString(), eq(Duration.ofSeconds(300)));
    }

    @Test @DisplayName("获取快照")
    void getSnapshot() throws Exception {
        QueueSnapshot snap = QueueSnapshot.builder()
                .snapshotId("test-id").skillGroupId(1L).entries(Collections.emptyList())
                .capturedAt(System.currentTimeMillis()).totalEntries(0).avgRiskScore(0.0).build();
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        when(redisService.getQueueSnapshot(1L)).thenReturn(Optional.of(mapper.writeValueAsString(snap)));

        Optional<QueueSnapshot> result = service.getSnapshot(1L);
        assertThat(result).isPresent();
        assertThat(result.get().getSnapshotId()).isEqualTo("test-id");
    }

    @Test @DisplayName("快照不存在返回空")
    void snapshotNotFound() {
        when(redisService.getQueueSnapshot(99L)).thenReturn(Optional.empty());

        Optional<QueueSnapshot> result = service.getSnapshot(99L);
        assertThat(result).isEmpty();
    }

    @Test @DisplayName("从快照恢复Redis sorted set")
    void recoverFromSnapshot() {
        QueueEntry e1 = qe(1L, 1L);
        QueueSnapshot snap = QueueSnapshot.builder()
                .snapshotId("test-id").skillGroupId(1L).entries(List.of(e1))
                .capturedAt(System.currentTimeMillis()).totalEntries(1).avgRiskScore(0.0).build();
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        try {
            when(redisService.getQueueSnapshot(1L)).thenReturn(Optional.of(mapper.writeValueAsString(snap)));
        } catch (Exception e) { throw new RuntimeException(e); }
        when(queueEntryMapper.selectBySessionId(1L)).thenReturn(e1);
        when(sessionMapper.selectStatus(1L)).thenReturn("WAITING");

        int recovered = service.recoverFromSnapshot(1L);
        assertThat(recovered).isEqualTo(1);
        verify(redisService).addToQueue(eq(1L), eq(1L), anyDouble());
    }

    @Test @DisplayName("无快照时恢复返回0")
    void recoverNoSnapshot() {
        when(redisService.getQueueSnapshot(1L)).thenReturn(Optional.empty());

        int recovered = service.recoverFromSnapshot(1L);
        assertThat(recovered).isEqualTo(0);
    }

    private QueueEntry qe(long sid, long sg) {
        QueueEntry q = new QueueEntry();
        q.setSessionId(sid); q.setSkillGroupId(sg); q.setCustomerId(100L);
        q.setPriorityScore(50); q.setJoinedAt(LocalDateTime.now());
        return q;
    }
    private SlaRiskScore risk(long sessionId, double score) {
        return SlaRiskScore.builder().sessionId(sessionId).riskScore(score)
                .vipLevel(0).waitSeconds(60L).availableAgents(1).avgAgentLoad(0.5)
                .historicalAht(300L).agentHeartbeatOk(true).calculatedAt(System.currentTimeMillis())
                .build();
    }
}
