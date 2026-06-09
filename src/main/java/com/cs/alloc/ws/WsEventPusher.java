package com.cs.alloc.ws;

import com.cs.alloc.domain.SlaRiskHistory;
import com.cs.alloc.mapper.SlaRiskHistoryMapper;
import com.cs.alloc.service.MessageQueue;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
public class WsEventPusher {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Map<String, Set<WebSocketSession>> customerSessions = new ConcurrentHashMap<>();
    private final Map<String, Set<WebSocketSession>> agentSessions = new ConcurrentHashMap<>();
    private final SlaRiskHistoryMapper slaRiskHistoryMapper;

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
        Set<WebSocketSession> set = customerSessions.get(String.valueOf(customerId));
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
        Set<WebSocketSession> set = agentSessions.get(String.valueOf(agentId));
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

    /**
     * 校验 WS 推送的风险数据与 SlaRiskHistory DB 记录是否一致。
     * 对推送数据中每个 session 的 riskScore，查询 DB 最新记录比对。
     * 如果不一致则记录告警日志，但不阻止推送（推送数据来自引擎，是权威来源）。
     *
     * @param pushedData 从 MQ 接收到的风险推送数据
     * @return true 如果所有 session 的风险与 DB 一致，或无法校验
     */
    public boolean validateRiskConsistency(Map<String, Object> pushedData) {
        Object sessionRisksObj = pushedData.get("sessionRisks");
        if (sessionRisksObj == null) return true;

        if (sessionRisksObj instanceof java.util.List<?> sessionRisks) {
            boolean allConsistent = true;
            for (Object item : sessionRisks) {
                if (item instanceof Map<?, ?> riskMap) {
                    try {
                        Number sessionIdNum = (Number) riskMap.get("sessionId");
                        Number pushedScoreNum = (Number) riskMap.get("riskScore");
                        if (sessionIdNum == null || pushedScoreNum == null) continue;

                        long sessionId = sessionIdNum.longValue();
                        double pushedScore = pushedScoreNum.doubleValue();

                        // Query latest risk history for this session from DB
                        SlaRiskHistory latest = slaRiskHistoryMapper.selectLatestBySessionId(sessionId);
                        if (latest != null) {
                            double dbScore = latest.getRiskScore();
                            // Allow small floating-point tolerance
                            if (Math.abs(pushedScore - dbScore) > 0.5) {
                                log.warn("WS风险推送与DB不一致: sessionId={}, pushed={}, db={}",
                                        sessionId, pushedScore, dbScore);
                                allConsistent = false;
                            }
                        }
                    } catch (Exception e) {
                        log.debug("校验风险一致性时出错, 跳过", e);
                    }
                }
            }
            return allConsistent;
        }
        return true;
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
            try {
                Map<String, Object> data = MAPPER.readValue(msg, Map.class);
                // Validate risk consistency between pushed data and DB before broadcasting
                boolean consistent = validateRiskConsistency(data);
                if (!consistent) {
                    log.warn("SLA风险推送数据与DB存在不一致, 仍然推送但已记录告警");
                }
                broadcastToAgents("sla.risk.updated", data);
            } catch (Exception e) { log.error("SLA_RISK_UPDATED事件处理失败", e); }
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
