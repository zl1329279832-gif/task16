package com.customerservice.service;

import com.customerservice.exception.BusinessException;
import com.customerservice.mapper.AgentMapper;
import com.customerservice.model.dto.WsEvent;
import com.customerservice.model.entity.Agent;
import com.customerservice.model.enums.AgentStatus;
import com.customerservice.mq.MessageQueue;
import com.customerservice.mq.MqTopics;
import com.customerservice.websocket.WebSocketSessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
public class AgentService {
    private static final Logger log = LoggerFactory.getLogger(AgentService.class);
    private static final String AGENT_HEARTBEAT_KEY = "cs:agent:heartbeat:";
    private static final int DISCONNECT_GRACE_SECONDS = 30;

    private final AgentMapper agentMapper;
    private final StringRedisTemplate redisTemplate;
    private final WebSocketSessionManager wsSessionManager;
    private final MessageQueue messageQueue;
    private final AuditService auditService;

    public AgentService(AgentMapper agentMapper,
                        StringRedisTemplate redisTemplate,
                        WebSocketSessionManager wsSessionManager,
                        MessageQueue messageQueue,
                        AuditService auditService) {
        this.agentMapper = agentMapper;
        this.redisTemplate = redisTemplate;
        this.wsSessionManager = wsSessionManager;
        this.messageQueue = messageQueue;
        this.auditService = auditService;
    }

    public Agent getAgent(Long agentId) {
        Agent agent = agentMapper.selectById(agentId);
        if (agent == null) throw new BusinessException("Agent not found: " + agentId);
        return agent;
    }

    /**
     * Change agent status. When going offline, triggers reallocation of active sessions.
     */
    public Agent changeStatus(Long agentId, AgentStatus newStatus) {
        Agent agent = getAgent(agentId);
        AgentStatus oldStatus = agent.getStatus();

        if (oldStatus == newStatus) return agent;

        agentMapper.updateStatus(agentId, newStatus.name());

        if (newStatus == AgentStatus.ONLINE) {
            agentMapper.updateLastOnlineAt(agentId);
            redisTemplate.opsForValue().set(
                    AGENT_HEARTBEAT_KEY + agentId, "1", 60, TimeUnit.SECONDS);
        } else if (newStatus == AgentStatus.OFFLINE) {
            redisTemplate.delete(AGENT_HEARTBEAT_KEY + agentId);
        }

        agent.setStatus(newStatus);

        // Notify via MQ
        Map<String, Object> event = new HashMap<>();
        event.put("agentId", agentId);
        event.put("oldStatus", oldStatus.name());
        event.put("newStatus", newStatus.name());
        messageQueue.publish(MqTopics.AGENT_STATUS_CHANGED, event);

        // Broadcast to all agents
        wsSessionManager.broadcastToAllAgents(
                WsEvent.of("AGENT_STATUS", Map.of(
                        "agentId", agentId,
                        "displayName", agent.getDisplayName(),
                        "status", newStatus.name()
                )));

        auditService.log("AGENT", agentId, "STATUS_CHANGE", "AGENT", agentId, event);
        log.info("Agent [{}] status: {} -> {}", agentId, oldStatus, newStatus);
        return agent;
    }

    /**
     * Handle agent WebSocket reconnection: restore heartbeat, keep status.
     */
    public void handleAgentReconnect(Long agentId) {
        Agent agent = agentMapper.selectById(agentId);
        if (agent == null) return;

        redisTemplate.opsForValue().set(
                AGENT_HEARTBEAT_KEY + agentId, "1", 60, TimeUnit.SECONDS);

        // If agent was marked offline due to disconnect, restore to ONLINE
        if (agent.getStatus() == AgentStatus.OFFLINE) {
            changeStatus(agentId, AgentStatus.ONLINE);
            log.info("Agent [{}] reconnected, restored to ONLINE", agentId);
        }
    }

    /**
     * Handle agent WebSocket disconnect: start grace period before marking offline.
     * Uses Redis key expiry to give the agent time to reconnect.
     */
    public void handleAgentDisconnect(Long agentId) {
        // Set a short-lived key; if it expires without reconnect, scheduler marks offline
        redisTemplate.opsForValue().set(
                AGENT_HEARTBEAT_KEY + agentId, "disconnected",
                DISCONNECT_GRACE_SECONDS, TimeUnit.SECONDS);
        log.info("Agent [{}] disconnected, grace period {}s", agentId, DISCONNECT_GRACE_SECONDS);
    }

    /**
     * Check if agent heartbeat is still alive in Redis.
     */
    public boolean isAgentAlive(Long agentId) {
        String val = redisTemplate.opsForValue().get(AGENT_HEARTBEAT_KEY + agentId);
        return val != null && !"disconnected".equals(val);
    }

    public void updateMaxConcurrent(Long agentId, int maxConcurrent) {
        if (maxConcurrent < 1 || maxConcurrent > 50) {
            throw new BusinessException("Max concurrent must be between 1 and 50");
        }
        agentMapper.updateMaxConcurrent(agentId, maxConcurrent);
        messageQueue.publish(MqTopics.AGENT_STATUS_CHANGED,
                Map.of("agentId", agentId, "maxConcurrentChanged", maxConcurrent));
    }

    public void incrementLoad(Long agentId) {
        agentMapper.incrementLoad(agentId);
    }

    public void decrementLoad(Long agentId) {
        agentMapper.decrementLoad(agentId);
    }

    public List<Agent> getOnlineAgents() {
        return agentMapper.selectAllOnline();
    }
}
