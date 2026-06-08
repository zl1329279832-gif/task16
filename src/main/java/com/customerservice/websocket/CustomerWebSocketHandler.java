package com.customerservice.websocket;

import com.customerservice.model.dto.MessageRequest;
import com.customerservice.model.dto.WsEvent;
import com.customerservice.service.MessageService;
import com.customerservice.service.AgentService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * Central WebSocket handler.
 *
 * Inbound events from clients:
 *   { "event": "MESSAGE",    "sessionId": 1, "data": { "content": "hello", "contentType": "TEXT", "messageUid": "uuid" } }
 *   { "event": "HEARTBEAT" }
 *   { "event": "MSG_READ",   "sessionId": 1, "data": { "sequenceNo": 5 } }
 *
 * Outbound events pushed by the server:
 *   QUEUE_POSITION      - periodic queue position updates
 *   MESSAGE             - new chat message
 *   SESSION_ASSIGNED    - session assigned to an agent
 *   SESSION_TRANSFERRED - session transferred to another agent
 *   SESSION_CLOSED      - session closed
 *   AGENT_STATUS        - agent status change broadcast
 *   SYSTEM_NOTICE       - system notification
 *   ERROR               - error notification
 *   HEARTBEAT           - heartbeat ack
 */
@Component
public class CustomerWebSocketHandler extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(CustomerWebSocketHandler.class);

    private final WebSocketSessionManager sessionManager;
    private final MessageService messageService;
    private final AgentService agentService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CustomerWebSocketHandler(WebSocketSessionManager sessionManager,
                                    @Lazy MessageService messageService,
                                    @Lazy AgentService agentService) {
        this.sessionManager = sessionManager;
        this.messageService = messageService;
        this.agentService = agentService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String role = (String) session.getAttributes().get("role");
        if ("customer".equals(role)) {
            String uid = (String) session.getAttributes().get("uid");
            sessionManager.registerCustomer(uid, session);
        } else if ("agent".equals(role)) {
            Long agentId = (Long) session.getAttributes().get("agentId");
            sessionManager.registerAgent(agentId, session);
            agentService.handleAgentReconnect(agentId);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        try {
            JsonNode node = objectMapper.readTree(message.getPayload());
            String event = node.path("event").asText();

            switch (event) {
                case "MESSAGE":
                    handleIncomingMessage(session, node);
                    break;
                case "HEARTBEAT":
                    sendHeartbeatAck(session);
                    break;
                case "MSG_READ":
                    // Could update message status to READ
                    break;
                default:
                    log.warn("Unknown WS event: {}", event);
            }
        } catch (Exception e) {
            log.error("Error handling WS message from session {}", session.getId(), e);
            sendError(session, "Invalid message format");
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String role = (String) session.getAttributes().get("role");
        if ("agent".equals(role)) {
            Long agentId = (Long) session.getAttributes().get("agentId");
            agentService.handleAgentDisconnect(agentId);
        }
        sessionManager.removeSession(session);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("WS transport error, session={}", session.getId(), exception);
        sessionManager.removeSession(session);
    }

    private void handleIncomingMessage(WebSocketSession session, JsonNode node) {
        Long sessionId = node.path("sessionId").asLong();
        JsonNode data = node.path("data");

        MessageRequest req = new MessageRequest();
        req.setSessionId(sessionId);
        req.setContent(data.path("content").asText());
        req.setContentType(data.path("contentType").asText("TEXT"));
        req.setMessageUid(data.path("messageUid").asText());

        String role = (String) session.getAttributes().get("role");
        if ("customer".equals(role)) {
            String uid = (String) session.getAttributes().get("uid");
            req.setSenderType("CUSTOMER");
        } else {
            Long agentId = (Long) session.getAttributes().get("agentId");
            req.setSenderType("AGENT");
            req.setSenderId(agentId);
        }

        messageService.sendMessage(req);
    }

    private void sendHeartbeatAck(WebSocketSession session) {
        try {
            String json = objectMapper.writeValueAsString(WsEvent.of("HEARTBEAT", "pong"));
            synchronized (session) {
                session.sendMessage(new TextMessage(json));
            }
        } catch (Exception e) {
            log.error("Failed to send heartbeat ack", e);
        }
    }

    private void sendError(WebSocketSession session, String msg) {
        try {
            String json = objectMapper.writeValueAsString(WsEvent.of("ERROR", msg));
            synchronized (session) {
                session.sendMessage(new TextMessage(json));
            }
        } catch (Exception e) {
            log.error("Failed to send error message", e);
        }
    }
}
