package com.customerservice.websocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * Extracts identity from query params during WebSocket handshake.
 * In production, replace with JWT/token-based auth.
 *
 * Connect as customer: ws://host/ws/chat?role=customer&uid=CUSTOMER_UID
 * Connect as agent:    ws://host/ws/chat?role=agent&agentId=123
 */
@Component
public class WebSocketAuthInterceptor implements HandshakeInterceptor {
    private static final Logger log = LoggerFactory.getLogger(WebSocketAuthInterceptor.class);

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (request instanceof ServletServerHttpRequest) {
            ServletServerHttpRequest servletRequest = (ServletServerHttpRequest) request;
            String role = servletRequest.getServletRequest().getParameter("role");
            if ("customer".equals(role)) {
                String uid = servletRequest.getServletRequest().getParameter("uid");
                if (uid == null || uid.isEmpty()) {
                    log.warn("Customer connection rejected: missing uid");
                    return false;
                }
                attributes.put("role", "customer");
                attributes.put("uid", uid);
            } else if ("agent".equals(role)) {
                String agentIdStr = servletRequest.getServletRequest().getParameter("agentId");
                if (agentIdStr == null) {
                    log.warn("Agent connection rejected: missing agentId");
                    return false;
                }
                attributes.put("role", "agent");
                attributes.put("agentId", Long.parseLong(agentIdStr));
            } else {
                log.warn("Connection rejected: invalid role [{}]", role);
                return false;
            }
        }
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // no-op
    }
}
