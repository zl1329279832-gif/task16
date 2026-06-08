package com.customerservice.service;

import com.customerservice.exception.BusinessException;
import com.customerservice.mapper.CustomerMapper;
import com.customerservice.mapper.SessionMapper;
import com.customerservice.mapper.SkillGroupMapper;
import com.customerservice.model.dto.SessionRequest;
import com.customerservice.model.dto.TransferRequest;
import com.customerservice.model.dto.WsEvent;
import com.customerservice.model.entity.*;
import com.customerservice.model.enums.*;
import com.customerservice.mq.MessageQueue;
import com.customerservice.mq.MqTopics;
import com.customerservice.websocket.WebSocketSessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

@Service
public class SessionService {
    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    private final SessionMapper sessionMapper;
    private final CustomerMapper customerMapper;
    private final SkillGroupMapper skillGroupMapper;
    private final QueueService queueService;
    private final AllocationService allocationService;
    private final AgentService agentService;
    private final MessageService messageService;
    private final AuditService auditService;
    private final WebSocketSessionManager wsSessionManager;
    private final StringRedisTemplate redisTemplate;
    private final MessageQueue messageQueue;

    public SessionService(SessionMapper sessionMapper,
                          CustomerMapper customerMapper,
                          SkillGroupMapper skillGroupMapper,
                          QueueService queueService,
                          AllocationService allocationService,
                          AgentService agentService,
                          MessageService messageService,
                          AuditService auditService,
                          WebSocketSessionManager wsSessionManager,
                          StringRedisTemplate redisTemplate,
                          MessageQueue messageQueue) {
        this.sessionMapper = sessionMapper;
        this.customerMapper = customerMapper;
        this.skillGroupMapper = skillGroupMapper;
        this.queueService = queueService;
        this.allocationService = allocationService;
        this.agentService = agentService;
        this.messageService = messageService;
        this.auditService = auditService;
        this.wsSessionManager = wsSessionManager;
        this.redisTemplate = redisTemplate;
        this.messageQueue = messageQueue;
    }

    /**
     * Create a new customer service session.
     * 1. Find or create the customer
     * 2. Create the session
     * 3. Try immediate allocation; if no agent available, enqueue
     */
    @Transactional
    public ChatSession createSession(SessionRequest req) {
        // Find or create customer
        Customer customer = customerMapper.selectByCustomerUid(req.getCustomerUid());
        if (customer == null) {
            customer = new Customer();
            customer.setCustomerUid(req.getCustomerUid());
            customer.setName(req.getCustomerName() != null ? req.getCustomerName() : "");
            customer.setVipLevel(VipLevel.NORMAL);
            customerMapper.insert(customer);
        }

        // Cache customer UID mapping for queue broadcast
        redisTemplate.opsForValue().set(
                "cs:customer:uid:" + customer.getId(), customer.getCustomerUid());

        // Resolve skill group
        Long skillGroupId = null;
        if (req.getSkillGroup() != null) {
            SkillGroup sg = skillGroupMapper.selectByName(req.getSkillGroup());
            if (sg != null) skillGroupId = sg.getId();
        }

        // Create session
        ChatSession session = new ChatSession();
        session.setSessionNo(generateSessionNo());
        session.setCustomerId(customer.getId());
        session.setSkillGroupId(skillGroupId);
        session.setVipLevel(customer.getVipLevel());
        session.setStatus(SessionStatus.QUEUING);
        session.setQueueStartAt(LocalDateTime.now());
        session.setLastActiveAt(LocalDateTime.now());
        session.setMetadata(req.getMetadata());
        sessionMapper.insert(session);

        // Try immediate allocation
        QueueEntry entry = queueService.enqueue(session);
        Agent agent = allocationService.allocate(entry);

        if (agent != null) {
            assignSessionToAgent(session, agent);
        } else {
            // Notify customer of queue position
            int position = queueService.getPosition(session.getId());
            wsSessionManager.sendToCustomer(customer.getCustomerUid(),
                    WsEvent.of("QUEUE_POSITION", session.getId(),
                            Map.of("position", position, "estimatedWaitSeconds", position * 120)));
        }

        messageQueue.publish(MqTopics.SESSION_CREATED, Map.of("sessionId", session.getId()));
        auditService.log("SYSTEM", null, "SESSION_CREATE", "SESSION", session.getId());
        return session;
    }

    /**
     * Assign a session to an agent: update DB, notify both parties, dequeue.
     */
    @Transactional
    public void assignSessionToAgent(ChatSession session, Agent agent) {
        sessionMapper.updateAgentAndStatus(session.getId(), agent.getId(),
                SessionStatus.ACTIVE.name());
        agentService.incrementLoad(agent.getId());
        queueService.dequeue(session.getId());

        session.setAgentId(agent.getId());
        session.setStatus(SessionStatus.ACTIVE);

        // Notify customer
        Customer customer = customerMapper.selectById(session.getCustomerId());
        if (customer != null) {
            wsSessionManager.sendToCustomer(customer.getCustomerUid(),
                    WsEvent.of("SESSION_ASSIGNED", session.getId(),
                            Map.of("agentId", agent.getId(),
                                    "agentName", agent.getDisplayName(),
                                    "sessionNo", session.getSessionNo())));
        }

        // Notify agent
        wsSessionManager.sendToAgent(agent.getId(),
                WsEvent.of("SESSION_ASSIGNED", session.getId(),
                        Map.of("sessionId", session.getId(),
                                "sessionNo", session.getSessionNo(),
                                "customerId", session.getCustomerId(),
                                "vipLevel", session.getVipLevel().name())));

        // Send system message
        messageService.sendSystemMessage(session.getId(),
                "Session assigned to agent: " + agent.getDisplayName());

        messageQueue.publish(MqTopics.SESSION_ASSIGNED,
                Map.of("sessionId", session.getId(), "agentId", agent.getId()));
        log.info("Session [{}] assigned to agent [{}]", session.getId(), agent.getId());
    }

    /**
     * Transfer session to another agent or skill group.
     * Maintains message continuity — messages are NOT duplicated.
     */
    @Transactional
    public ChatSession transferSession(TransferRequest req) {
        ChatSession session = sessionMapper.selectById(req.getSessionId());
        if (session == null) throw new BusinessException("Session not found");
        if (session.getStatus() == SessionStatus.CLOSED) {
            throw new BusinessException("Cannot transfer a closed session");
        }

        Long fromAgentId = session.getAgentId();

        // Mark as transferring to prevent race conditions
        sessionMapper.updateStatus(session.getId(), SessionStatus.TRANSFERRING.name());

        Agent newAgent = allocationService.allocateForTransfer(
                session.getId(), fromAgentId,
                req.getTargetAgentId(), req.getTargetSkillGroupId());

        if (newAgent == null) {
            // Rollback status
            sessionMapper.updateStatus(session.getId(), SessionStatus.ACTIVE.name());
            throw new BusinessException("No available agent for transfer");
        }

        // Update session
        sessionMapper.updateAgentAndStatus(session.getId(), newAgent.getId(),
                SessionStatus.ACTIVE.name());

        // Adjust load counters
        if (fromAgentId != null) {
            agentService.decrementLoad(fromAgentId);
        }
        agentService.incrementLoad(newAgent.getId());

        session.setAgentId(newAgent.getId());
        session.setStatus(SessionStatus.ACTIVE);

        // Notify all parties
        Customer customer = customerMapper.selectById(session.getCustomerId());

        if (customer != null) {
            wsSessionManager.sendToCustomer(customer.getCustomerUid(),
                    WsEvent.of("SESSION_TRANSFERRED", session.getId(),
                            Map.of("newAgentName", newAgent.getDisplayName())));
        }
        if (fromAgentId != null) {
            wsSessionManager.sendToAgent(fromAgentId,
                    WsEvent.of("SESSION_TRANSFERRED", session.getId(),
                            Map.of("direction", "out", "toAgent", newAgent.getDisplayName())));
        }
        wsSessionManager.sendToAgent(newAgent.getId(),
                WsEvent.of("SESSION_TRANSFERRED", session.getId(),
                        Map.of("direction", "in", "sessionNo", session.getSessionNo(),
                                "customerId", session.getCustomerId())));

        String reason = req.getReason() != null ? req.getReason() : "";
        messageService.sendSystemMessage(session.getId(),
                "Session transferred to " + newAgent.getDisplayName() +
                        (reason.isEmpty() ? "" : ". Reason: " + reason));

        messageQueue.publish(MqTopics.SESSION_TRANSFERRED,
                Map.of("sessionId", session.getId(),
                        "fromAgentId", fromAgentId != null ? fromAgentId : "",
                        "toAgentId", newAgent.getId()));
        auditService.log("AGENT", fromAgentId, "SESSION_TRANSFER", "SESSION", session.getId());
        return session;
    }

    /**
     * Supervisor force-takeover of a session.
     */
    @Transactional
    public ChatSession takeoverSession(Long sessionId, Long supervisorId) {
        Agent supervisor = agentService.getAgent(supervisorId);
        if (!supervisor.getIsSupervisor()) {
            throw new BusinessException("Only supervisors can takeover sessions");
        }

        ChatSession session = sessionMapper.selectById(sessionId);
        if (session == null) throw new BusinessException("Session not found");

        Long fromAgentId = session.getAgentId();

        sessionMapper.updateAgentAndStatus(sessionId, supervisorId, SessionStatus.ACTIVE.name());
        if (fromAgentId != null) {
            agentService.decrementLoad(fromAgentId);
        }
        agentService.incrementLoad(supervisorId);
        allocationService.logTakeover(sessionId, fromAgentId, supervisorId);

        session.setAgentId(supervisorId);
        session.setStatus(SessionStatus.ACTIVE);

        // Notify
        if (fromAgentId != null) {
            wsSessionManager.sendToAgent(fromAgentId,
                    WsEvent.of("SESSION_TRANSFERRED", sessionId,
                            Map.of("direction", "out", "toAgent", supervisor.getDisplayName(),
                                    "reason", "Supervisor takeover")));
        }
        wsSessionManager.sendToAgent(supervisorId,
                WsEvent.of("SESSION_ASSIGNED", sessionId,
                        Map.of("sessionId", sessionId, "sessionNo", session.getSessionNo(),
                                "takeover", true)));

        messageService.sendSystemMessage(sessionId,
                "Session taken over by supervisor: " + supervisor.getDisplayName());

        auditService.log("SUPERVISOR", supervisorId, "SESSION_TAKEOVER", "SESSION", sessionId);
        return session;
    }

    /**
     * Suspend (hold) a session.
     */
    @Transactional
    public ChatSession suspendSession(Long sessionId, Long agentId) {
        ChatSession session = sessionMapper.selectById(sessionId);
        if (session == null) throw new BusinessException("Session not found");
        if (session.getStatus() != SessionStatus.ACTIVE) {
            throw new BusinessException("Can only suspend active sessions");
        }

        sessionMapper.updateStatus(sessionId, SessionStatus.SUSPENDED.name());
        session.setStatus(SessionStatus.SUSPENDED);

        messageService.sendSystemMessage(sessionId, "Session suspended by agent");

        auditService.log("AGENT", agentId, "SESSION_SUSPEND", "SESSION", sessionId);
        return session;
    }

    /**
     * Resume a suspended session.
     */
    @Transactional
    public ChatSession resumeSession(Long sessionId, Long agentId) {
        ChatSession session = sessionMapper.selectById(sessionId);
        if (session == null) throw new BusinessException("Session not found");
        if (session.getStatus() != SessionStatus.SUSPENDED) {
            throw new BusinessException("Can only resume suspended sessions");
        }

        sessionMapper.updateStatus(sessionId, SessionStatus.ACTIVE.name());
        session.setStatus(SessionStatus.ACTIVE);

        messageService.sendSystemMessage(sessionId, "Session resumed");

        auditService.log("AGENT", agentId, "SESSION_RESUME", "SESSION", sessionId);
        return session;
    }

    /**
     * Close a session.
     */
    @Transactional
    public ChatSession closeSession(Long sessionId, Long operatorId,
                                    String operatorType, CloseReason reason) {
        ChatSession session = sessionMapper.selectById(sessionId);
        if (session == null) throw new BusinessException("Session not found");
        if (session.getStatus() == SessionStatus.CLOSED) return session;

        sessionMapper.closeSession(sessionId, reason.name());

        // Release agent capacity
        if (session.getAgentId() != null) {
            agentService.decrementLoad(session.getAgentId());
        }

        // Remove from queue if still queuing
        queueService.dequeue(sessionId);

        session.setStatus(SessionStatus.CLOSED);
        session.setCloseReason(reason);

        // Notify both parties
        Customer customer = customerMapper.selectById(session.getCustomerId());
        Map<String, Object> closeData = Map.of(
                "reason", reason.name(), "sessionNo", session.getSessionNo());

        if (customer != null) {
            wsSessionManager.sendToCustomer(customer.getCustomerUid(),
                    WsEvent.of("SESSION_CLOSED", sessionId, closeData));
        }
        if (session.getAgentId() != null) {
            wsSessionManager.sendToAgent(session.getAgentId(),
                    WsEvent.of("SESSION_CLOSED", sessionId, closeData));
        }

        messageService.sendSystemMessage(sessionId, "Session closed: " + reason.name());

        messageQueue.publish(MqTopics.SESSION_CLOSED,
                Map.of("sessionId", sessionId, "reason", reason.name()));
        auditService.log(operatorType, operatorId, "SESSION_CLOSE", "SESSION", sessionId);
        return session;
    }

    public ChatSession getSession(Long sessionId) {
        ChatSession session = sessionMapper.selectById(sessionId);
        if (session == null) throw new BusinessException("Session not found: " + sessionId);
        return session;
    }

    public List<ChatSession> getAgentSessions(Long agentId) {
        return sessionMapper.selectActiveByAgentId(agentId);
    }

    public List<ChatSession> getCustomerSessions(Long customerId) {
        return sessionMapper.selectByCustomerId(customerId);
    }

    /**
     * Try to allocate queued sessions. Called periodically by scheduler.
     */
    public void processQueue() {
        List<QueueEntry> queue = queueService.getOrderedQueue(null);
        for (QueueEntry entry : queue) {
            Agent agent = allocationService.allocate(entry);
            if (agent != null) {
                ChatSession session = sessionMapper.selectById(entry.getSessionId());
                if (session != null && session.getStatus() == SessionStatus.QUEUING) {
                    assignSessionToAgent(session, agent);
                }
            }
        }
    }

    private String generateSessionNo() {
        return "CS" + System.currentTimeMillis() + "-" +
                UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    }
}
