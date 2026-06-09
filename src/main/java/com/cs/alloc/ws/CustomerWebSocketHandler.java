package com.cs.alloc.ws;

import com.cs.alloc.domain.Message;
import com.cs.alloc.domain.Session;
import com.cs.alloc.service.MessageService;
import com.cs.alloc.service.QueueService;
import com.cs.alloc.service.RedisService;
import com.cs.alloc.service.SessionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
public class CustomerWebSocketHandler extends TextWebSocketHandler {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final WsEventPusher pusher;
    private final MessageService messageService;
    private final SessionService sessionService;
    private final RedisService redisService;
    private final QueueService queueService;

    public CustomerWebSocketHandler(WsEventPusher pusher, MessageService messageService, SessionService sessionService, QueueService queueService, RedisService redisService) {
        this.pusher = pusher; this.messageService = messageService; this.sessionService = sessionService; this.redisService = redisService; this.queueService = queueService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String customerId = extractParam(session, "customerId");
        if (customerId == null) { try { session.close(CloseStatus.BAD_DATA); } catch (Exception ignored) {} return; }
        pusher.registerCustomer(customerId, session);
        try {
            Map<String, Object> ackData = new HashMap<>();
            ackData.put("customerId", customerId);
            java.util.Optional<Long> activeSessionId = redisService.getCustomerSession(Long.parseLong(customerId));
            if (activeSessionId.isPresent()) {
                Session activeSession = sessionService.getSession(activeSessionId.get());
                if (activeSession != null && !"CLOSED".equals(activeSession.getStatus())) {
                    Map<String, Object> restoreData = new HashMap<>();
                    restoreData.put("sessionId", activeSession.getId());
                    restoreData.put("sessionNo", activeSession.getSessionNo());
                    restoreData.put("status", activeSession.getStatus());
                    restoreData.put("agentId", activeSession.getAgentId());
                    if ("WAITING".equals(activeSession.getStatus())) {
                        restoreData.put("queuePosition", queueService.getPosition(activeSession.getId()));
                    }
                    ackData.put("activeSession", restoreData);
                }
            }
            session.sendMessage(new TextMessage(MAPPER.writeValueAsString(Map.of("event", "connected", "data", ackData, "timestamp", System.currentTimeMillis()))));
        } catch (Exception e) { log.error("发送连接确认失败", e); }
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String customerId = extractParam(session, "customerId");
        if (customerId == null) return;
        try {
            Map<String, Object> payload = MAPPER.readValue(message.getPayload(), Map.class);
            String event = String.valueOf(payload.get("event"));
            switch (event) {
                case "send_message" -> {
                    long sid = ((Number) payload.get("sessionId")).longValue();
                    String content = String.valueOf(payload.get("content"));
                    String msgType = payload.containsKey("msgType") ? String.valueOf(payload.get("msgType")) : "TEXT";
                    String idKey = payload.containsKey("idempotencyKey") ? String.valueOf(payload.get("idempotencyKey")) : null;
                    Message msg = messageService.sendMessage(sid, customerId, "CUSTOMER", content, msgType, idKey);
                    var s = sessionService.getSession(sid);
                    if (s != null && s.getAgentId() != null) pusher.pushToAgent(s.getAgentId(), "chat.message", Map.of("sessionId", sid, "message", msg));
                }
                case "cancel_queue" -> sessionService.closeSession(((Number) payload.get("sessionId")).longValue(), customerId, "CUSTOMER", "客户取消排队");
                case "ping" -> session.sendMessage(new TextMessage(MAPPER.writeValueAsString(Map.of("event", "pong", "timestamp", System.currentTimeMillis()))));
            }
        } catch (Exception e) { log.error("处理客户消息失败", e); }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String customerId = extractParam(session, "customerId");
        if (customerId != null) pusher.unregisterCustomer(customerId, session);
    }

    private String extractParam(WebSocketSession session, String param) {
        URI uri = session.getUri();
        if (uri == null || uri.getQuery() == null) return null;
        for (String part : uri.getQuery().split("&")) { String[] kv = part.split("=", 2); if (kv.length == 2 && kv[0].equals(param)) return kv[1]; }
        return null;
    }
}
