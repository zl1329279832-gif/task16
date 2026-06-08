package com.customerservice.scheduler;

import com.customerservice.mapper.AgentMapper;
import com.customerservice.mapper.SessionMapper;
import com.customerservice.model.entity.Agent;
import com.customerservice.model.entity.ChatSession;
import com.customerservice.model.enums.AgentStatus;
import com.customerservice.model.enums.CloseReason;
import com.customerservice.service.AgentService;
import com.customerservice.service.QueueService;
import com.customerservice.service.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Scheduled tasks for queue processing, idle timeout, and agent health checks.
 */
@Component
public class ServiceScheduler {
    private static final Logger log = LoggerFactory.getLogger(ServiceScheduler.class);

    private final SessionService sessionService;
    private final QueueService queueService;
    private final AgentService agentService;
    private final SessionMapper sessionMapper;
    private final AgentMapper agentMapper;

    @Value("${cs.session.idle-timeout-seconds:300}")
    private int idleTimeoutSeconds;

    public ServiceScheduler(SessionService sessionService,
                            QueueService queueService,
                            AgentService agentService,
                            SessionMapper sessionMapper,
                            AgentMapper agentMapper) {
        this.sessionService = sessionService;
        this.queueService = queueService;
        this.agentService = agentService;
        this.sessionMapper = sessionMapper;
        this.agentMapper = agentMapper;
    }

    /**
     * Process queue: try to allocate waiting sessions to available agents.
     * Runs every 10 seconds.
     */
    @Scheduled(fixedDelayString = "${cs.allocation.retry-interval-seconds:10}000")
    public void processQueue() {
        try {
            sessionService.processQueue();
        } catch (Exception e) {
            log.error("Error processing queue", e);
        }
    }

    /**
     * Broadcast queue positions to waiting customers.
     * Runs every 5 seconds.
     */
    @Scheduled(fixedDelayString = "${cs.queue.position-broadcast-interval:5}000")
    public void broadcastQueuePositions() {
        try {
            queueService.broadcastQueuePositions(null);
        } catch (Exception e) {
            log.error("Error broadcasting queue positions", e);
        }
    }

    /**
     * Check for idle sessions (customer not responding).
     * Runs every 60 seconds.
     */
    @Scheduled(fixedDelay = 60000)
    public void checkIdleSessions() {
        try {
            List<ChatSession> idleSessions = sessionMapper.selectIdleSessions(idleTimeoutSeconds);
            for (ChatSession session : idleSessions) {
                log.info("Session [{}] idle timeout, closing", session.getId());
                sessionService.closeSession(session.getId(), null, "SYSTEM", CloseReason.TIMEOUT);
            }
        } catch (Exception e) {
            log.error("Error checking idle sessions", e);
        }
    }

    /**
     * Check agent heartbeats and mark disconnected agents as offline.
     * Runs every 15 seconds.
     */
    @Scheduled(fixedDelay = 15000)
    public void checkAgentHealth() {
        try {
            List<Agent> onlineAgents = agentService.getOnlineAgents();
            for (Agent agent : onlineAgents) {
                if (!agentService.isAgentAlive(agent.getId())) {
                    log.info("Agent [{}] heartbeat lost, marking offline", agent.getId());
                    agentService.changeStatus(agent.getId(), AgentStatus.OFFLINE);

                    // Re-queue active sessions of this agent
                    List<ChatSession> activeSessions = sessionMapper.selectActiveByAgentId(agent.getId());
                    for (ChatSession session : activeSessions) {
                        log.info("Re-queuing session [{}] from offline agent [{}]",
                                session.getId(), agent.getId());
                        // Put back to queue for reallocation
                        sessionMapper.updateStatus(session.getId(), "QUEUING");
                        agentMapper.decrementLoad(agent.getId());
                        sessionMapper.updateAgentAndStatus(session.getId(), null, "QUEUING");
                        queueService.enqueue(session);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error checking agent health", e);
        }
    }

    /**
     * Refresh queue priority scores (aging factor).
     * Runs every 30 seconds.
     */
    @Scheduled(fixedDelay = 30000)
    public void refreshQueuePriorities() {
        try {
            queueService.refreshPriorities();
        } catch (Exception e) {
            log.error("Error refreshing queue priorities", e);
        }
    }
}
