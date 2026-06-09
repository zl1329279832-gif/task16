package com.cs.alloc.ws;

import com.cs.alloc.domain.Message;
import com.cs.alloc.domain.Session;
import com.cs.alloc.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.*;

@Slf4j
@Component
public class AgentWebSocketHandler extends TextWebSocketHandler {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final WsEventPusher pusher;
    private final MessageService messageService;
    private final SessionService sessionService;
    private final RedisService redisService;
    private final MessageQueue messageQueue;
    private final Map<String, ScheduledFuture<?>> reconnectTimers = new ConcurrentHashMap<>();
    private final ScheduledExecutorService reconnectScheduler = Executors.newSingleThreadScheduledExecutor();

    public AgentWebSocketHandler(WsEventPusher pusher, MessageService messageService,
                                 SessionService sessionService, RedisService redisService,
                                 MessageQueue messageQueue) {
        this.pusher = pusher;
        this.messageService = messageService;
        this.sessionService = sessionService;
        this.redisService = redisService;
        this.messageQueue = messageQueue;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String agentId = extractParam(session, "agentId");
        if (agentId == null) {
            try { session.close(CloseStatus.BAD_DATA); } catch (Exception ignored) {}
            return;
        }
        pusher.registerAgent(agentId, session);

        // 取消之前的离线定时器 (防止误触发 agentOffline)
        ScheduledFuture<?> timer = reconnectTimers.remove(agentId);
        boolean isReconnect = timer != null;
        if (timer != null) {
            timer.cancel(false);
            log.info("客服 {} 断线重连, 已取消离线定时器", agentId);
        }

        try {
            java.util.Map<String, Object> ackData = new java.util.HashMap<>();
            ackData.put("agentId", agentId);
            ackData.put("reconnect", isReconnect);
            if (isReconnect) {
                ackData.put("activeSessions",
                        sessionService.getAgentActiveSessions(Long.parseLong(agentId)));
            }
            session.sendMessage(new TextMessage(MAPPER.writeValueAsString(
                    Map.of("event", "connected", "data", ackData,
                            "timestamp", System.currentTimeMillis()))));
        } catch (Exception ignored) {}

        // 确保心跳键存在 (防止心跳过期但 WS 仍在线的不一致)
        redisService.heartbeat(Long.parseLong(agentId));
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String agentId = extractParam(session, "agentId");
        if (agentId == null) return;
        try {
            // 每条消息都刷新心跳 (不仅仅是显式 heartbeat 事件)
            redisService.heartbeat(Long.parseLong(agentId));

            Map<String, Object> payload = MAPPER.readValue(message.getPayload(), Map.class);
            String event = String.valueOf(payload.get("event"));
            switch (event) {
                case "send_message" -> {
                    long sid = ((Number) payload.get("sessionId")).longValue();
                    Message msg = messageService.sendMessage(sid, agentId, "AGENT",
                            String.valueOf(payload.get("content")),
                            payload.containsKey("msgType") ? String.valueOf(payload.get("msgType")) : "TEXT",
                            payload.containsKey("idempotencyKey") ? String.valueOf(payload.get("idempotencyKey")) : null);
                    Session s = sessionService.getSession(sid);
                    if (s != null) pusher.pushToCustomer(s.getCustomerId(), "chat.message",
                            Map.of("sessionId", sid, "message", msg));
                }
                case "accept_session" -> sessionService.acceptSession(
                        ((Number) payload.get("sessionId")).longValue(), Long.parseLong(agentId));
                case "transfer_session" -> sessionService.transferSession(
                        ((Number) payload.get("sessionId")).longValue(), Long.parseLong(agentId),
                        payload.containsKey("toAgentId") ? ((Number) payload.get("toAgentId")).longValue() : null,
                        payload.containsKey("toSkillGroupId") ? ((Number) payload.get("toSkillGroupId")).longValue() : null,
                        payload.containsKey("reason") ? String.valueOf(payload.get("reason")) : null);
                case "suspend_session" -> sessionService.suspendSession(
                        ((Number) payload.get("sessionId")).longValue(), Long.parseLong(agentId));
                case "resume_session" -> sessionService.resumeSession(
                        ((Number) payload.get("sessionId")).longValue(), Long.parseLong(agentId));
                case "close_session" -> sessionService.closeSession(
                        ((Number) payload.get("sessionId")).longValue(), agentId, "AGENT",
                        payload.containsKey("reason") ? String.valueOf(payload.get("reason")) : null);
                case "heartbeat" -> {
                    redisService.heartbeat(Long.parseLong(agentId));
                    session.sendMessage(new TextMessage(MAPPER.writeValueAsString(
                            Map.of("event", "heartbeat_ack", "timestamp", System.currentTimeMillis()))));
                }
            }
        } catch (Exception e) { log.error("处理客服消息失败", e); }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String agentId = extractParam(session, "agentId");
        if (agentId == null) return;
        pusher.unregisterAgent(agentId, session);
        if (status != CloseStatus.NORMAL) {
            // 调度 30 秒后检查: 如果仍未重连则执行离线处理
            ScheduledFuture<?> future = reconnectScheduler.schedule(() -> {
                reconnectTimers.remove(agentId);
                if (!pusher.isAgentConnected(agentId)) {
                    log.info("客服 {} 断线超时30秒, 执行离线处理", agentId);
                    try { sessionService.agentOffline(Long.parseLong(agentId)); }
                    catch (Exception e) { log.error("处理断线超时失败", e); }
                }
            }, 30, TimeUnit.SECONDS);
            // 存储定时器引用, 重连时可取消
            reconnectTimers.put(agentId, future);
            log.info("客服 {} 异常断线, 30秒内等待重连", agentId);
        }
    }

    private String extractParam(WebSocketSession session, String param) {
        URI uri = session.getUri();
        if (uri == null || uri.getQuery() == null) return null;
        for (String part : uri.getQuery().split("&")) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2 && kv[0].equals(param)) return kv[1];
        }
        return null;
    }
}
