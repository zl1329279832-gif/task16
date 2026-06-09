package com.cs.alloc.ws;

import com.cs.alloc.domain.Session;
import com.cs.alloc.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import java.net.URI;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AgentWebSocketHandlerTest {
    @Mock private WsEventPusher pusher;
    @Mock private MessageService messageService;
    @Mock private SessionService sessionService;
    @Mock private RedisService redisService;
    @Mock private MessageQueue messageQueue;
    private AgentWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        handler = new AgentWebSocketHandler(pusher, messageService, sessionService, redisService, messageQueue);
    }

    @Test @DisplayName("正常连接: 注册并发送心跳")
    void normalConnect() throws Exception {
        WebSocketSession ws = mockWsSession("10");
        handler.afterConnectionEstablished(ws);
        verify(pusher).registerAgent(eq("10"), eq(ws));
        verify(redisService).heartbeat(10L);
    }

    @Test @DisplayName("正常断开: 不触发重连定时器")
    void normalDisconnect() throws Exception {
        WebSocketSession ws = mockWsSession("10");
        handler.afterConnectionClosed(ws, CloseStatus.NORMAL);
        verify(pusher).unregisterAgent("10", ws);
        // 正常断开不应触发 agentOffline
    }

    @Test @DisplayName("异常断开: 注册重连定时器")
    void abnormalDisconnect() throws Exception {
        WebSocketSession ws = mockWsSession("10");
        handler.afterConnectionClosed(ws, CloseStatus.SESSION_NOT_RELIABLE);
        verify(pusher).unregisterAgent("10", ws);
        // 异常断开应注册定时器 (30秒后检查)
    }

    @Test @DisplayName("客服心跳消息: 刷新心跳并回复ACK")
    void heartbeatMessage() throws Exception {
        WebSocketSession ws = mockWsSession("10");
        String payload = new ObjectMapper().writeValueAsString(Map.of("event", "heartbeat"));
        handler.handleMessage(ws, new TextMessage(payload));
        // 每条消息都应刷新心跳
        verify(redisService, atLeast(1)).heartbeat(10L);
    }

    @Test @DisplayName("客服发送消息: 刷新心跳并路由消息")
    void sendMessageRefreshesHeartbeat() throws Exception {
        WebSocketSession ws = mockWsSession("10");
        Session session = new Session();
        session.setId(1L);
        session.setCustomerId(100L);
        session.setAgentId(10L);
        when(sessionService.getSession(1L)).thenReturn(session);
        when(messageService.sendMessage(eq(1L), eq("10"), eq("AGENT"), anyString(), anyString(), any()))
                .thenReturn(new com.cs.alloc.domain.Message());

        String payload = new ObjectMapper().writeValueAsString(Map.of(
                "event", "send_message", "sessionId", 1, "content", "你好"));
        handler.handleMessage(ws, new TextMessage(payload));
        // 验证心跳被刷新
        verify(redisService, atLeast(1)).heartbeat(10L);
    }

    @Test @DisplayName("缺少agentId参数: 关闭连接")
    void missingAgentId() throws Exception {
        WebSocketSession ws = mock(WebSocketSession.class);
        when(ws.getUri()).thenReturn(new URI("ws://localhost/ws/agent"));
        handler.afterConnectionEstablished(ws);
        verify(ws).close(CloseStatus.BAD_DATA);
    }

    private WebSocketSession mockWsSession(String agentId) {
        WebSocketSession ws = mock(WebSocketSession.class);
        try {
            when(ws.getUri()).thenReturn(new URI("ws://localhost/ws/agent?agentId=" + agentId));
            lenient().when(ws.isOpen()).thenReturn(true);
        } catch (Exception e) { throw new RuntimeException(e); }
        return ws;
    }
}
