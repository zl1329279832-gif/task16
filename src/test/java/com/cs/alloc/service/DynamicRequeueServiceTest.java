package com.cs.alloc.service;

import com.cs.alloc.domain.*;
import com.cs.alloc.mapper.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DynamicRequeueServiceTest {
    @Mock private QueueEntryMapper queueEntryMapper;
    @Mock private SessionMapper sessionMapper;
    @Mock private AgentMapper agentMapper;
    @Mock private RequeueAuditMapper requeueAuditMapper;
    @Mock private RedisService redisService;
    @Mock private MessageQueue messageQueue;
    @Mock private SlaRiskPredictor slaRiskPredictor;
    private DynamicRequeueService service;

    @BeforeEach
    void setUp() {
        service = new DynamicRequeueService(queueEntryMapper, sessionMapper, agentMapper,
                requeueAuditMapper, redisService, messageQueue, slaRiskPredictor);
        ReflectionTestUtils.setField(service, "fallbackSkillGroupId", 1L);
    }

    // ==================== 人工置顶 ====================

    @Test @DisplayName("人工置顶: 提升到队列最前面")
    void pinTopShouldPromoteToFront() {
        QueueEntry entry = qe(10L, 1L, 50, 3);
        QueueEntry afterPin = qe(10L, 1L, 10050, 1);
        when(queueEntryMapper.selectBySessionId(10L)).thenReturn(entry).thenReturn(afterPin);
        when(queueEntryMapper.selectBySkillGroupId(1L)).thenReturn(List.of(entry));

        service.pinTop(10L, "supervisor1");

        verify(queueEntryMapper).updatePinned(10L, true, "supervisor1");
        verify(queueEntryMapper).updatePriorityScore(eq(10L), eq(10050));
        verify(redisService).updateQueuePriority(eq(1L), eq(10L), eq((double) 10050));
        verify(requeueAuditMapper).insert(argThat(audit ->
                "PIN_TOP".equals(audit.getAction()) && audit.getSessionId() == 10L));
        verify(messageQueue).publish(eq(MessageQueue.Topics.QUEUE_REORDERED), anyString());
    }

    @Test @DisplayName("人工置顶: 不在排队中抛异常")
    void pinTopNotInQueueShouldThrow() {
        when(queueEntryMapper.selectBySessionId(10L)).thenReturn(null);
        assertThatThrownBy(() -> service.pinTop(10L, "op1"))
                .isInstanceOf(com.cs.alloc.common.BizException.class)
                .hasMessageContaining("不在排队中");
    }

    @Test @DisplayName("取消置顶: 恢复正常优先级")
    void unpinTopShouldRestore() {
        QueueEntry entry = qe(10L, 1L, 10050, 1);
        entry.setPinned(true);
        entry.setPinnedBy("supervisor1");
        entry.setJoinedAt(LocalDateTime.now().minusMinutes(5));
        QueueEntry afterUnpin = qe(10L, 1L, 30, 3);
        when(queueEntryMapper.selectBySessionId(10L)).thenReturn(entry).thenReturn(afterUnpin);
        when(queueEntryMapper.selectBySkillGroupId(1L)).thenReturn(List.of(entry));

        service.unpinTop(10L, "supervisor1");

        verify(queueEntryMapper).updatePinned(10L, false, null);
        verify(requeueAuditMapper).insert(argThat(audit -> "UNPIN".equals(audit.getAction())));
    }

    // ==================== VIP 插队 ====================

    @Test @DisplayName("VIP插队: VIP等级越高加权越大")
    void vipJumpShouldBoostPriority() {
        QueueEntry entry = qe(10L, 1L, 50, 5);
        QueueEntry afterJump = qe(10L, 1L, 3050, 1);
        when(queueEntryMapper.selectBySessionId(10L)).thenReturn(entry).thenReturn(afterJump);
        when(queueEntryMapper.selectBySkillGroupId(1L)).thenReturn(List.of(entry));

        service.vipJump(10L, 3);

        // VIP3 加权 3000
        verify(queueEntryMapper).updatePriorityScore(10L, 3050);
        verify(redisService).updateQueuePriority(1L, 10L, 3050);
        verify(requeueAuditMapper).insert(argThat(audit ->
                "VIP_JUMP".equals(audit.getAction()) && audit.getNewPriority() == 3050));
    }

    @Test @DisplayName("VIP插队: 普通用户不生效")
    void vipJumpIgnoresNonVip() {
        service.vipJump(10L, 0);
        verify(queueEntryMapper, never()).selectBySessionId(anyLong());
    }

    @Test @DisplayName("VIP插队: 多个VIP同时排队, 高等级VIP在前")
    void multipleVipRankingOrder() {
        QueueEntry vip1 = qe(10L, 1L, 50, 3);
        QueueEntry vip3 = qe(11L, 1L, 50, 5);

        // VIP1 插队 (加权 1000)
        when(queueEntryMapper.selectBySessionId(10L)).thenReturn(vip1).thenReturn(vip1);
        when(queueEntryMapper.selectBySkillGroupId(1L)).thenReturn(Arrays.asList(vip1, vip3));
        service.vipJump(10L, 1);

        // VIP3 插队 (加权 3000)
        when(queueEntryMapper.selectBySessionId(11L)).thenReturn(vip3).thenReturn(vip3);
        service.vipJump(11L, 3);

        // VIP3 加权更高
        ArgumentCaptor<Integer> captor = ArgumentCaptor.forClass(Integer.class);
        verify(queueEntryMapper, times(2)).updatePriorityScore(anyLong(), captor.capture());
        List<Integer> priorities = captor.getAllValues();
        assertThat(priorities.get(1)).isGreaterThan(priorities.get(0));
    }

    // ==================== 技能组降级兜底 ====================

    @Test @DisplayName("技能组降级: 原组无可用客服时降级到通用组")
    void skillFallbackWhenNoAgent() {
        QueueEntry entry = qe(10L, 2L, 50, 3);
        entry.setOriginalSkillGroupId(null);
        QueueEntry afterFallback = qe(10L, 1L, 50, 1);
        when(queueEntryMapper.selectBySessionId(10L)).thenReturn(entry).thenReturn(afterFallback);
        when(slaRiskPredictor.countAvailableAgents(2L)).thenReturn(0); // 原组无客服
        when(slaRiskPredictor.countAvailableAgents(1L)).thenReturn(2); // 通用组有客服
        when(queueEntryMapper.selectBySkillGroupId(anyLong())).thenReturn(Collections.emptyList());

        boolean result = service.skillFallback(10L);

        assertThat(result).isTrue();
        verify(redisService).removeFromQueue(2L, 10L);
        verify(queueEntryMapper).updateSkillGroupId(10L, 1L, 2L);
        verify(sessionMapper).updateSkillGroupId(10L, 1L);
        verify(redisService).addToQueue(1L, 10L, 50);
        verify(requeueAuditMapper).insert(argThat(audit -> "SKILL_FALLBACK".equals(audit.getAction())));
    }

    @Test @DisplayName("技能组降级: 原组有可用客服时不降级")
    void noFallbackWhenAgentAvailable() {
        QueueEntry entry = qe(10L, 2L, 50, 3);
        entry.setOriginalSkillGroupId(null);
        when(queueEntryMapper.selectBySessionId(10L)).thenReturn(entry);
        when(slaRiskPredictor.countAvailableAgents(2L)).thenReturn(1);

        boolean result = service.skillFallback(10L);

        assertThat(result).isFalse();
        verify(queueEntryMapper, never()).updateSkillGroupId(anyLong(), anyLong(), anyLong());
    }

    @Test @DisplayName("技能组降级: 已降级过不重复降级")
    void noDoubleFallback() {
        QueueEntry entry = qe(10L, 1L, 50, 3);
        entry.setOriginalSkillGroupId(2L); // 已从技能组2降级
        when(queueEntryMapper.selectBySessionId(10L)).thenReturn(entry);

        boolean result = service.skillFallback(10L);

        assertThat(result).isFalse();
    }

    @Test @DisplayName("技能组降级: 兜底组也无客服时不降级")
    void noFallbackWhenFallbackGroupAlsoEmpty() {
        QueueEntry entry = qe(10L, 2L, 50, 3);
        entry.setOriginalSkillGroupId(null);
        when(queueEntryMapper.selectBySessionId(10L)).thenReturn(entry);
        when(slaRiskPredictor.countAvailableAgents(2L)).thenReturn(0);
        when(slaRiskPredictor.countAvailableAgents(1L)).thenReturn(0);

        boolean result = service.skillFallback(10L);

        assertThat(result).isFalse();
    }

    // ==================== 多技能组并发入队 ====================

    @Test @DisplayName("多技能组并发入队: 不同技能组独立管理")
    void multiSkillGroupConcurrentEnqueue() {
        QueueEntry entry1 = qe(10L, 1L, 50, 1);
        QueueEntry entry2 = qe(11L, 2L, 80, 1);
        QueueEntry entry3 = qe(12L, 1L, 30, 2);

        when(queueEntryMapper.selectBySkillGroupId(1L)).thenReturn(Arrays.asList(entry1, entry3));
        when(queueEntryMapper.selectBySkillGroupId(2L)).thenReturn(List.of(entry2));

        // 重排技能组1
        service.reorderPositions(1L);
        verify(queueEntryMapper).updatePosition(entry1.getSessionId(), 1);
        verify(queueEntryMapper).updatePosition(entry3.getSessionId(), 2);

        // 重排技能组2 — 独立不受影响
        service.reorderPositions(2L);
        verify(queueEntryMapper).updatePosition(entry2.getSessionId(), 1);
    }

    // ==================== 客服离线 ====================

    @Test @DisplayName("客服离线: 触发高风险会话技能组降级")
    void agentOfflineShouldTriggerFallback() {
        QueueEntry entry = qe(10L, 2L, 50, 3);
        entry.setOriginalSkillGroupId(null);
        entry.setRiskScore(70); // 高风险
        QueueEntry afterFallback = qe(10L, 1L, 50, 1);
        when(queueEntryMapper.selectBySessionId(10L)).thenReturn(entry).thenReturn(afterFallback);
        when(slaRiskPredictor.countAvailableAgents(2L)).thenReturn(0); // 客服离线后无可用
        when(slaRiskPredictor.countAvailableAgents(1L)).thenReturn(3);
        when(queueEntryMapper.selectBySkillGroupId(anyLong())).thenReturn(Collections.emptyList());

        boolean result = service.skillFallback(10L);

        assertThat(result).isTrue();
        verify(requeueAuditMapper).insert(argThat(audit ->
                "SKILL_FALLBACK".equals(audit.getAction()) &&
                        audit.getDetail().contains("无可用客服")));
    }

    // ==================== SLA超时重排 ====================

    @Test @DisplayName("按风险分数重排: 高风险优先")
    void reorderByRiskPrioritizesHighRisk() {
        QueueEntry lowRisk = qe(10L, 1L, 50, 3);
        lowRisk.setRiskScore(20);
        lowRisk.setRiskLevel("LOW");
        QueueEntry highRisk = qe(11L, 1L, 30, 5);
        highRisk.setRiskScore(80);
        highRisk.setRiskLevel("HIGH");
        QueueEntry criticalRisk = qe(12L, 1L, 40, 4);
        criticalRisk.setRiskScore(90);
        criticalRisk.setRiskLevel("CRITICAL");

        when(queueEntryMapper.selectBySkillGroupId(1L)).thenReturn(
                new ArrayList<>(Arrays.asList(lowRisk, highRisk, criticalRisk)));

        service.reorderByRisk(1L);

        // 验证位置更新: CRITICAL -> HIGH -> LOW
        verify(queueEntryMapper).updatePosition(12L, 1); // CRITICAL 排第一
        verify(queueEntryMapper).updatePosition(11L, 2); // HIGH 排第二
        verify(queueEntryMapper).updatePosition(10L, 3); // LOW 排第三
    }

    @Test @DisplayName("按风险分数重排: 置顶优先于高风险")
    void reorderPinnedOverHighRisk() {
        QueueEntry pinned = qe(10L, 1L, 50, 3);
        pinned.setRiskScore(10);
        pinned.setPinned(true);
        QueueEntry critical = qe(11L, 1L, 30, 2);
        critical.setRiskScore(95);
        critical.setPinned(false);

        when(queueEntryMapper.selectBySkillGroupId(1L)).thenReturn(
                new ArrayList<>(Arrays.asList(critical, pinned)));

        service.reorderByRisk(1L);

        // 置顶优先于 CRITICAL
        verify(queueEntryMapper).updatePosition(10L, 1);
        verify(queueEntryMapper).updatePosition(11L, 2);
    }

    // ==================== 重排审计 ====================

    @Test @DisplayName("所有重排操作都记录审计日志")
    void allRequeueActionsAreAudited() {
        QueueEntry entry = qe(10L, 1L, 50, 3);
        QueueEntry afterUpdate = qe(10L, 1L, 1050, 1);
        when(queueEntryMapper.selectBySessionId(10L)).thenReturn(entry).thenReturn(afterUpdate);
        when(queueEntryMapper.selectBySkillGroupId(1L)).thenReturn(List.of(entry));

        service.pinTop(10L, "op1");
        verify(requeueAuditMapper).insert(any(RequeueAuditLog.class));

        reset(requeueAuditMapper, queueEntryMapper, redisService, messageQueue);
        entry.setPinned(true);
        entry.setPinnedBy("op1");
        entry.setJoinedAt(LocalDateTime.now().minusMinutes(1));
        when(queueEntryMapper.selectBySessionId(10L)).thenReturn(entry).thenReturn(afterUpdate);
        when(queueEntryMapper.selectBySkillGroupId(1L)).thenReturn(List.of(entry));
        service.unpinTop(10L, "op1");
        verify(requeueAuditMapper).insert(any(RequeueAuditLog.class));
    }

    @Test @DisplayName("查询审计日志: 按sessionId过滤")
    void queryAuditBySession() {
        List<RequeueAuditLog> logs = List.of(new RequeueAuditLog());
        when(requeueAuditMapper.selectBySessionId(10L)).thenReturn(logs);

        List<RequeueAuditLog> result = service.getAuditLogs(10L, null, 1, 20);
        assertThat(result).hasSize(1);
    }

    @Test @DisplayName("查询审计日志: 按action过滤")
    void queryAuditByAction() {
        when(requeueAuditMapper.selectByAction("VIP_JUMP", 0, 20)).thenReturn(Collections.emptyList());
        List<RequeueAuditLog> result = service.getAuditLogs(null, "VIP_JUMP", 1, 20);
        assertThat(result).isEmpty();
    }

    // ==================== 辅助方法 ====================

    private QueueEntry qe(long sessionId, long skillGroupId, int priorityScore, int position) {
        QueueEntry q = new QueueEntry();
        q.setSessionId(sessionId);
        q.setSkillGroupId(skillGroupId);
        q.setCustomerId(100L + sessionId);
        q.setPriorityScore(priorityScore);
        q.setPosition(position);
        q.setRiskScore(0);
        q.setRiskLevel("LOW");
        q.setPinned(false);
        q.setJoinedAt(LocalDateTime.now());
        return q;
    }
}
