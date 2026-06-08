package com.customerservice.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.customerservice.model.dto.WsEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Manages WebSocket sessions for customers and agents.
 * Supports reconnection by mapping user identities to sessions.
 */
@Component
public class WebSocketSessionManager {
    private static final Logger log = LoggerFactory.getLogger(WebSocketSessionManager.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    // customerUid -> sessions (a customer may have multiple tabs)
    private final Map<String, Set<WebSocketSession>> customerSessions = new ConcurrentHashMap<>();
    // agentId -> sessions
    private final Map<Long, Set<WebSocketSession>> agentSessions = new ConcurrentHashMap<>();
    // sessionId -> WebSocketSession for reverse lookup
    private final Map<String, Object[]> sessionIdentity = new ConcurrentHashMap<>();

    public void registerCustomer(String customerUid, WebSocketSession session) {
        customerSessions.computeIfAbsent(customerUid, k -> new CopyOnWriteArraySet<>()).add(session);
        sessionIdentity.put(session.getId(), new Object[]{"customer", customerUid});
        log.info("Customer [{}] connected, wsSession={}", customerUid, session.getId());
    }

    public void registerAgent(Long agentId, WebSocketSession session) {
        agentSessions.computeIfAbsent(agentId, k -> new CopyOnWriteArraySet<>()).add(session);
        sessionIdentity.put(session.getId(), new Object[]{"agent", agentId});
        log.info("Agent [{}] connected, wsSession={}", agentId, session.getId());
    }

    public void removeSession(WebSocketSession session) {
        Object[] identity = sessionIdentity.remove(session.getId());
        if (identity == null) return;

        if ("customer".equals(identity[0])) {
            String uid = (String) identity[1];
            Set<WebSocketSession> sessions = customerSessions.get(uid);
            if (sessions != null) {
                sessions.remove(session);
                if (sessions.isEmpty()) customerSessions.remove(uid);
            }
            log.info("Customer [{}] disconnected", uid);
        } else {
            Long agentId = (Long) identity[1];
            Set<WebSocketSession> sessions = agentSessions.get(agentId);
            if (sessions != null) {
                sessions.remove(session);
                if (sessions.isEmpty()) agentSessions.remove(agentId);
            }
            log.info("Agent [{}] disconnected", agentId);
        }
    }

    public void sendToCustomer(String customerUid, WsEvent event) {
        Set<WebSocketSession> sessions = customerSessions.get(customerUid);
        if (sessions != null) {
            sessions.forEach(s -> sendMessage(s, event));
        }
    }

    public void sendToAgent(Long agentId, WsEvent event) {
        Set<WebSocketSession> sessions = agentSessions.get(agentId);
        if (sessions != null) {
            sessions.forEach(s -> sendMessage(s, event));
        }
    }

    public void broadcastToAllAgents(WsEvent event) {
        agentSessions.values().forEach(sessions ->
                sessions.forEach(s -> sendMessage(s, event)));
    }

    public boolean isCustomerOnline(String customerUid) {
        Set<WebSocketSession> sessions = customerSessions.get(customerUid);
        return sessions != null && sessions.stream().anyMatch(WebSocketSession::isOpen);
    }

    public boolean isAgentOnline(Long agentId) {
        Set<WebSocketSession> sessions = agentSessions.get(agentId);
        return sessions != null && sessions.stream().anyMatch(WebSocketSession::isOpen);
    }

    public Object[] getIdentity(WebSocketSession session) {
        return sessionIdentity.get(session.getId());
    }

    private void sendMessage(WebSocketSession session, WsEvent event) {
        if (!session.isOpen()) return;
        try {
            String json = objectMapper.writeValueAsString(event);
            synchronized (session) {
                session.sendMessage(new TextMessage(json));
            }
        } catch (IOException e) {
            log.error("Failed to send WS message to session {}", session.getId(), e);
        }
    }
}
