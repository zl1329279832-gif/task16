package com.cs.alloc.ws;

import com.cs.alloc.service.InMemoryMessageQueue;
import com.cs.alloc.service.MessageQueue;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import java.net.URI;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WsEventPusherTest {
    private WsEventPusher pusher;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeEach
    void setUp() {
        pusher = new WsEventPusher();
    }

    @Test @DisplayName("SLA风险推送包含完整数据和seq序列号")
    void slaRiskPushContainsSeqAndData() throws Exception {
        WebSocketSession ws = mockAgentSession("1");
        pusher.registerAgent("1", ws);

        Map<String, Object> data = Map.of(
                "criticalAlert", true,
                "maxRisk", 85.0,
                "sessionCount", 3,
                "riskSummaries", java.util.List.of(Map.of("skillGroupId", 1, "maxRisk", 85.0))
        );
        pusher.pushSlaRiskUpdate(1L, data);

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(ws).sendMessage(captor.capture());
        String json = captor.getValue().getPayload();

        @SuppressWarnings("unchecked")
        Map<String, Object> msg = MAPPER.readValue(json, Map.class);
        assertThat(msg.get("event")).isEqualTo("sla.risk.updated");
        assertThat(msg.get("seq")).isNotNull();
        assertThat(((Number) msg.get("seq")).longValue()).isGreaterThan(0);
        assertThat(msg.get("timestamp")).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> msgData = (Map<String, Object>) msg.get("data");
        assertThat(msgData.get("criticalAlert")).isEqualTo(true);
        assertThat(msgData.get("riskSummaries")).isNotNull();
    }

    @Test @DisplayName("多次推送seq递增")
    void pushSeqIncrementing() throws Exception {
        WebSocketSession ws = mockAgentSession("1");
        pusher.registerAgent("1", ws);

        pusher.pushSlaRiskUpdate(1L, Map.of("test", "first"));
        pusher.pushSlaRiskUpdate(1L, Map.of("test", "second"));

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(ws, times(2)).sendMessage(captor.capture());

        @SuppressWarnings("unchecked")
        Map<String, Object> msg1 = MAPPER.readValue(captor.getAllValues().get(0).getPayload(), Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> msg2 = MAPPER.readValue(captor.getAllValues().get(1).getPayload(), Map.class);

        long seq1 = ((Number) msg1.get("seq")).longValue();
        long seq2 = ((Number) msg2.get("seq")).longValue();
        assertThat(seq2).isGreaterThan(seq1);
    }

    @Test @DisplayName("MQ桥接: SLA_RISK_UPDATED事件透传到WS")
    void mqBridgeSlaRiskUpdatedPassthrough() throws Exception {
        WebSocketSession ws = mockAgentSession("1");
        pusher.registerAgent("1", ws);

        MessageQueue mq = new InMemoryMessageQueue();
        pusher.initMqBridge(mq);

        // Simulate SlaRiskEngine publishing with riskSummaries
        String mqMsg = "{\"criticalAlert\":true,\"timestamp\":1234567890,\"riskSummaries\":[{\"skillGroupId\":1,\"maxRisk\":85.0,\"avgRisk\":72.5,\"sessionCount\":3}]}";
        mq.publish(MessageQueue.Topics.SLA_RISK_UPDATED, mqMsg);

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(ws).sendMessage(captor.capture());
        String json = captor.getValue().getPayload();

        @SuppressWarnings("unchecked")
        Map<String, Object> msg = MAPPER.readValue(json, Map.class);
        assertThat(msg.get("event")).isEqualTo("sla.risk.updated");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) msg.get("data");
        assertThat(data.get("criticalAlert")).isEqualTo(true);
        assertThat(data.get("riskSummaries")).isNotNull();
    }

    @Test @DisplayName("MQ桥接: QUEUE_REORDERED事件透传")
    void mqBridgeQueueReorderedPassthrough() throws Exception {
        WebSocketSession ws = mockAgentSession("1");
        pusher.registerAgent("1", ws);

        MessageQueue mq = new InMemoryMessageQueue();
        pusher.initMqBridge(mq);

        mq.publish(MessageQueue.Topics.QUEUE_REORDERED,
                "{\"skillGroupId\":1,\"count\":5,\"timestamp\":1234567890}");

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(ws).sendMessage(captor.capture());
        String json = captor.getValue().getPayload();

        @SuppressWarnings("unchecked")
        Map<String, Object> msg = MAPPER.readValue(json, Map.class);
        assertThat(msg.get("event")).isEqualTo("queue.reordered");
        assertThat(msg.get("seq")).isNotNull();
    }

    @Test @DisplayName("MQ桥接: 降级事件透传")
    void mqBridgeDegradationPassthrough() throws Exception {
        WebSocketSession ws = mockAgentSession("1");
        pusher.registerAgent("1", ws);

        MessageQueue mq = new InMemoryMessageQueue();
        pusher.initMqBridge(mq);

        mq.publish(MessageQueue.Topics.SKILLGROUP_DEGRADED,
                "{\"skillGroupId\":1,\"fallbackId\":99,\"count\":3,\"timestamp\":1234567890}");

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(ws).sendMessage(captor.capture());

        @SuppressWarnings("unchecked")
        Map<String, Object> msg = MAPPER.readValue(captor.getValue().getPayload(), Map.class);
        assertThat(msg.get("event")).isEqualTo("skillgroup.degraded");
    }

    @Test @DisplayName("未注册客服不收到推送")
    void unregisteredAgentNoPush() {
        pusher.pushSlaRiskUpdate(1L, Map.of("test", "data"));
        // no exception, just no-op
    }

    @Test @DisplayName("客服断开后不再收到推送")
    void disconnectedAgentNoPush() throws Exception {
        WebSocketSession ws = mockAgentSession("1");
        pusher.registerAgent("1", ws);
        pusher.unregisterAgent("1", ws);

        pusher.pushSlaRiskUpdate(1L, Map.of("test", "data"));

        verify(ws, never()).sendMessage(any());
    }

    @Test @DisplayName("replaceCustomerSession关闭旧连接")
    void replaceCustomerSessionClosesOld() throws Exception {
        WebSocketSession oldWs = mockCustomerSession("100");
        WebSocketSession newWs = mockCustomerSession("100");
        when(newWs.getId()).thenReturn("new-session-id");

        pusher.registerCustomer("100", oldWs);
        pusher.replaceCustomerSession("100", newWs);

        verify(oldWs).close(any());
    }

    private WebSocketSession mockAgentSession(String agentId) {
        WebSocketSession ws = mock(WebSocketSession.class);
        try {
            lenient().when(ws.getUri()).thenReturn(new URI("ws://localhost/ws/agent?agentId=" + agentId));
            lenient().when(ws.isOpen()).thenReturn(true);
            lenient().when(ws.getId()).thenReturn("ws-agent-" + agentId);
        } catch (Exception e) { throw new RuntimeException(e); }
        return ws;
    }

    private WebSocketSession mockCustomerSession(String customerId) {
        WebSocketSession ws = mock(WebSocketSession.class);
        try {
            lenient().when(ws.getUri()).thenReturn(new URI("ws://localhost/ws/customer?customerId=" + customerId));
            lenient().when(ws.isOpen()).thenReturn(true);
            lenient().when(ws.getId()).thenReturn("ws-customer-" + customerId + "-" + System.nanoTime());
        } catch (Exception e) { throw new RuntimeException(e); }
        return ws;
    }
}
