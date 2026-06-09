package com.cs.alloc.ws;

import com.cs.alloc.service.MessageQueue;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

@Slf4j
@Component
public class WsEventPusher {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Map<String, Set<WebSocketSession>> customerSessions = new ConcurrentHashMap<>();
    private final Map<String, Set<WebSocketSession>> agentSessions = new ConcurrentHashMap<>();

    public void registerCustomer(String customerId, WebSocketSession session) {
        customerSessions.computeIfAbsent(customerId, k -> new CopyOnWriteArraySet<>()).add(session);
    }
    public void unregisterCustomer(String customerId, WebSocketSession session) {
        Set<WebSocketSession> set = customerSessions.get(customerId);
        if (set != null) set.remove(session);
    }
    /**
     * 替换客户的 WS 连接 (处理页面刷新场景):
     * 关闭旧连接, 绑定新连接, 保证同一客户只有一个活跃 WS。
     */
    public void replaceCustomerSession(String customerId, WebSocketSession newSession) {
        Set<WebSocketSession> sessions = customerSessions.computeIfAbsent(customerId, k -> new CopyOnWriteArraySet<>());
        for (WebSocketSession old : sessions) {
            if (old.isOpen() && !old.getId().equals(newSession.getId())) {
                try { old.close(CloseStatus.NORMAL); } catch (Exception ignored) {}
            }
        }
        sessions.clear();
        sessions.add(newSession);
    }
    public boolean isCustomerConnected(String customerId) {
        Set<WebSocketSession> set = customerSessions.get(customerId);
        return set != null && !set.isEmpty();
    }
    public void registerAgent(String agentId, WebSocketSession session) {
        agentSessions.computeIfAbsent(agentId, k -> new CopyOnWriteArraySet<>()).add(session);
    }
    public void unregisterAgent(String agentId, WebSocketSession session) {
        Set<WebSocketSession> set = agentSessions.get(agentId);
        if (set != null) set.remove(session);
    }
    public boolean isAgentConnected(String agentId) {
        Set<WebSocketSession> set = agentSessions.get(agentId);
        return set != null && !set.isEmpty();
    }

    public void pushToCustomer(long customerId, String event, Map<String, Object> data) {
        String msg = buildMessage(event, data);
        Set<WebSocketSession> sessions = customerSessions.get(String.valueOf(customerId));
        if (sessions != null) for (WebSocketSession ws : sessions) sendText(ws, msg);
    }
    public void pushToAgent(long agentId, String event, Map<String, Object> data) {
        String msg = buildMessage(event, data);
        Set<WebSocketSession> sessions = agentSessions.get(String.valueOf(agentId));
        if (sessions != null) for (WebSocketSession ws : sessions) sendText(ws, msg);
    }
    public void broadcastToAgents(String event, Map<String, Object> data) {
        String msg = buildMessage(event, data);
        for (Set<WebSocketSession> sessions : agentSessions.values()) for (WebSocketSession ws : sessions) sendText(ws, msg);
    }
    public void pushSlaRiskUpdate(long skillGroupId, Map<String, Object> data) {
        broadcastToAgents("sla.risk.updated", data);
    }
    public void pushQueueReordered(long skillGroupId, Map<String, Object> data) {
        broadcastToAgents("queue.reordered", data);
    }
    public void pushDegradationAlert(long skillGroupId, Map<String, Object> data) {
        broadcastToAgents("skillgroup.degraded", data);
    }
    public void pushDegradationRestored(long skillGroupId, Map<String, Object> data) {
        broadcastToAgents("skillgroup.restored", data);
    }

    private String buildMessage(String event, Map<String, Object> data) {
        try { return MAPPER.writeValueAsString(Map.of("event", event, "data", data, "timestamp", System.currentTimeMillis())); }
        catch (Exception e) { log.error("构建WS消息失败", e); return "{}"; }
    }
    private void sendText(WebSocketSession ws, String text) {
        if (ws.isOpen()) { try { synchronized (ws) { ws.sendMessage(new TextMessage(text)); } } catch (IOException e) { log.warn("WS发送失败: {}", e.getMessage()); } }
    }

    @SuppressWarnings("unchecked")
    public void initMqBridge(MessageQueue mq) {
        mq.subscribe(MessageQueue.Topics.SESSION_ALLOCATED, msg -> {
            try { Map<String, Object> data = MAPPER.readValue(msg, Map.class);
                pushToCustomer(((Number) data.get("customerId")).longValue(), "session.assigned", data);
                pushToAgent(((Number) data.get("agentId")).longValue(), "session.assigned", data);
            } catch (Exception e) { log.error("SESSION_ALLOCATED事件处理失败", e); }
        });
        mq.subscribe(MessageQueue.Topics.SESSION_TRANSFERRED, msg -> {
            try { Map<String, Object> data = MAPPER.readValue(msg, Map.class);
                pushToAgent(((Number) data.get("fromAgent")).longValue(), "session.transferred", data);
                Object toAgent = data.get("toAgent");
                if (toAgent != null && !"null".equals(String.valueOf(toAgent))) pushToAgent(((Number) toAgent).longValue(), "session.new_transfer", data);
            } catch (Exception e) { log.error("SESSION_TRANSFERRED事件处理失败", e); }
        });
        mq.subscribe(MessageQueue.Topics.SESSION_CLOSED, msg -> {
            try { Map<String, Object> data = MAPPER.readValue(msg, Map.class);
                pushToCustomer(((Number) data.get("customerId")).longValue(), "session.closed", data);
            } catch (Exception e) { log.error("SESSION_CLOSED事件处理失败", e); }
        });
        mq.subscribe(MessageQueue.Topics.CHAT_MESSAGE, msg -> { /* handled per-session routing */ });
        mq.subscribe(MessageQueue.Topics.AGENT_STATUS, msg -> {
            try { Map<String, Object> data = MAPPER.readValue(msg, Map.class); broadcastToAgents("agent.status_changed", data); }
            catch (Exception e) { log.error("AGENT_STATUS事件处理失败", e); }
        });
        mq.subscribe(MessageQueue.Topics.QUEUE_UPDATED, msg -> {
            try { Map<String, Object> data = MAPPER.readValue(msg, Map.class);
                Object cid = data.get("customerId");
                if (cid != null) pushToCustomer(((Number) cid).longValue(), "queue.updated", data);
            } catch (Exception e) { log.error("QUEUE_UPDATED事件处理失败", e); }
        });
        mq.subscribe(MessageQueue.Topics.SYSTEM_NOTICE, msg -> {
            try { Map<String, Object> data = MAPPER.readValue(msg, Map.class); broadcastToAgents("system.notice", data); }
            catch (Exception e) { log.error("SYSTEM_NOTICE事件处理失败", e); }
        });
        mq.subscribe(MessageQueue.Topics.SLA_RISK_UPDATED, msg -> {
            try { Map<String, Object> data = MAPPER.readValue(msg, Map.class); broadcastToAgents("sla.risk.updated", data); }
            catch (Exception e) { log.error("SLA_RISK_UPDATED事件处理失败", e); }
        });
        mq.subscribe(MessageQueue.Topics.QUEUE_REORDERED, msg -> {
            try { Map<String, Object> data = MAPPER.readValue(msg, Map.class); broadcastToAgents("queue.reordered", data); }
            catch (Exception e) { log.error("QUEUE_REORDERED事件处理失败", e); }
        });
        mq.subscribe(MessageQueue.Topics.SKILLGROUP_DEGRADED, msg -> {
            try { Map<String, Object> data = MAPPER.readValue(msg, Map.class); broadcastToAgents("skillgroup.degraded", data); }
            catch (Exception e) { log.error("SKILLGROUP_DEGRADED事件处理失败", e); }
        });
        mq.subscribe(MessageQueue.Topics.SKILLGROUP_RESTORED, msg -> {
            try { Map<String, Object> data = MAPPER.readValue(msg, Map.class); broadcastToAgents("skillgroup.restored", data); }
            catch (Exception e) { log.error("SKILLGROUP_RESTORED事件处理失败", e); }
        });
        log.info("MQ → WebSocket 事件桥接初始化完成");
    }
}
