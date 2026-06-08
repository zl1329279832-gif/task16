package com.cs.alloc.service;

import com.cs.alloc.common.BizException;
import com.cs.alloc.domain.*;
import com.cs.alloc.mapper.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class SessionService {
    private final SessionMapper sessionMapper;
    private final CustomerMapper customerMapper;
    private final AgentMapper agentMapper;
    private final AgentStateMapper agentStateMapper;
    private final AllocationLogMapper allocationLogMapper;
    private final AuditLogMapper auditLogMapper;
    private final QueueService queueService;
    private final RedisService redisService;
    private final MessageQueue messageQueue;

    @Transactional
    public Session createSession(long customerId, long skillGroupId) {
        redisService.getCustomerSession(customerId).ifPresent(existingId -> {
            Session existing = sessionMapper.selectById(existingId);
            if (existing != null && !existing.getStatus().equals("CLOSED")) {
                throw new BizException("您已有进行中的会话: " + existing.getSessionNo());
            }
        });
        Customer customer = customerMapper.selectById(customerId);
        if (customer == null) throw new BizException("客户不存在");
        Session session = new Session();
        session.setSessionNo(generateSessionNo());
        session.setCustomerId(customerId);
        session.setSkillGroupId(skillGroupId);
        session.setStatus("WAITING");
        session.setPriorityScore(0);
        sessionMapper.insert(session);
        queueService.join(session, customer.getVipLevel());
        audit("SYSTEM", "SYSTEM", "SESSION_CREATE", "SESSION", String.valueOf(session.getId()), String.format("客户%d进入排队, 技能组%d", customerId, skillGroupId));
        return session;
    }

    @Transactional
    public Session acceptSession(long sessionId, long agentId) {
        Session session = requireSession(sessionId);
        requireStatus(session, "WAITING");
        Agent agent = requireAgent(agentId);
        if (!redisService.hasCapacity(agentId)) throw new BizException("客服已达最大接待量");
        if (!redisService.tryLock("session:" + sessionId, Duration.ofSeconds(10))) throw new BizException("会话正在被分配");
        try {
            sessionMapper.assignAgent(sessionId, agentId, "ASSIGNED");
            redisService.incrementAgentLoad(agentId);
            redisService.bindSessionToAgent(sessionId, agentId);
            redisService.setCustomerSession(session.getCustomerId(), sessionId);
            agentStateMapper.updateLoad(agentId, redisService.getAgentLoad(agentId));
            queueService.leave(sessionId);
            AllocationLog allocLog = new AllocationLog();
            allocLog.setSessionId(sessionId); allocLog.setAgentId(agentId); allocLog.setAction("ALLOCATE"); allocLog.setReason("客服主动接入");
            allocationLogMapper.insert(allocLog);
            audit(String.valueOf(agentId), "AGENT", "SESSION_ACCEPT", "SESSION", String.valueOf(sessionId), "客服主动接入");
            messageQueue.publish(MessageQueue.Topics.SESSION_ALLOCATED, String.format("{\"sessionId\":%d,\"agentId\":%d,\"customerId\":%d}", sessionId, agentId, session.getCustomerId()));
            return sessionMapper.selectById(sessionId);
        } finally { redisService.unlock("session:" + sessionId); }
    }

    @Transactional
    public Session transferSession(long sessionId, long fromAgentId, Long toAgentId, Long toSkillGroupId, String reason) {
        Session session = requireSession(sessionId);
        requireStatus(session, "ASSIGNED", "ACTIVE");
        if (!redisService.tryLock("session:" + sessionId, Duration.ofSeconds(10))) throw new BizException("会话正在操作中");
        try {
            long currentAgentId = session.getAgentId();
            sessionMapper.updateStatus(sessionId, "TRANSFERRING");
            redisService.unbindSessionFromAgent(sessionId, currentAgentId);
            redisService.decrementAgentLoad(currentAgentId);
            agentStateMapper.updateLoad(currentAgentId, redisService.getAgentLoad(currentAgentId));
            if (toAgentId != null) {
                Agent toAgent = requireAgent(toAgentId);
                if (!redisService.hasCapacity(toAgentId)) throw new BizException("目标客服已满");
                sessionMapper.assignAgent(sessionId, toAgentId, "ASSIGNED");
                redisService.incrementAgentLoad(toAgentId);
                redisService.bindSessionToAgent(sessionId, toAgentId);
                agentStateMapper.updateLoad(toAgentId, redisService.getAgentLoad(toAgentId));
            } else {
                if (toSkillGroupId != null) session.setSkillGroupId(toSkillGroupId);
                session.setTransferFrom(currentAgentId);
                sessionMapper.updateStatus(sessionId, "WAITING");
                queueService.rejoin(session);
            }
            AllocationLog allocLog = new AllocationLog();
            allocLog.setSessionId(sessionId); allocLog.setAgentId(currentAgentId); allocLog.setAction("TRANSFER"); allocLog.setReason(reason != null ? reason : "客服转接");
            allocationLogMapper.insert(allocLog);
            audit(String.valueOf(fromAgentId), "AGENT", "SESSION_TRANSFER", "SESSION", String.valueOf(sessionId), String.format("从客服%d转接", currentAgentId));
            messageQueue.publish(MessageQueue.Topics.SESSION_TRANSFERRED, String.format("{\"sessionId\":%d,\"fromAgent\":%d,\"toAgent\":%s}", sessionId, currentAgentId, toAgentId != null ? toAgentId : "null"));
            return sessionMapper.selectById(sessionId);
        } finally { redisService.unlock("session:" + sessionId); }
    }

    @Transactional
    public Session suspendSession(long sessionId, long agentId) {
        Session session = requireSession(sessionId);
        requireStatus(session, "ASSIGNED", "ACTIVE");
        sessionMapper.updateStatus(sessionId, "SUSPENDED");
        audit(String.valueOf(agentId), "AGENT", "SESSION_SUSPEND", "SESSION", String.valueOf(sessionId), "客服挂起");
        return sessionMapper.selectById(sessionId);
    }

    @Transactional
    public Session resumeSession(long sessionId, long agentId) {
        Session session = requireSession(sessionId);
        requireStatus(session, "SUSPENDED");
        sessionMapper.updateStatus(sessionId, "ACTIVE");
        audit(String.valueOf(agentId), "AGENT", "SESSION_RESUME", "SESSION", String.valueOf(sessionId), "客服恢复");
        return sessionMapper.selectById(sessionId);
    }

    @Transactional
    public void closeSession(long sessionId, String operatorId, String operatorType, String reason) {
        Session session = requireSession(sessionId);
        if ("CLOSED".equals(session.getStatus())) return;
        if (!redisService.tryLock("session:" + sessionId, Duration.ofSeconds(10))) throw new BizException("会话正在操作中");
        try {
            sessionMapper.close(sessionId);
            if (session.getAgentId() != null) {
                redisService.unbindSessionFromAgent(sessionId, session.getAgentId());
                redisService.decrementAgentLoad(session.getAgentId());
                agentStateMapper.updateLoad(session.getAgentId(), redisService.getAgentLoad(session.getAgentId()));
            }
            redisService.clearCustomerSession(session.getCustomerId());
            queueService.leave(sessionId);
            audit(operatorId, operatorType, "SESSION_CLOSE", "SESSION", String.valueOf(sessionId), reason != null ? reason : "会话结束");
            messageQueue.publish(MessageQueue.Topics.SESSION_CLOSED, String.format("{\"sessionId\":%d,\"customerId\":%d}", sessionId, session.getCustomerId()));
        } finally { redisService.unlock("session:" + sessionId); }
    }

    @Transactional
    public Session supervisorTakeover(long sessionId, long supervisorId) {
        Session session = requireSession(sessionId);
        requireStatus(session, "ASSIGNED", "ACTIVE", "SUSPENDED");
        Agent supervisor = requireAgent(supervisorId);
        if (!Boolean.TRUE.equals(supervisor.getIsSupervisor())) throw new BizException("仅主管可执行接管操作");
        if (!redisService.tryLock("session:" + sessionId, Duration.ofSeconds(10))) throw new BizException("会话正在操作中");
        try {
            Long oldAgentId = session.getAgentId();
            if (oldAgentId != null) {
                redisService.unbindSessionFromAgent(sessionId, oldAgentId);
                redisService.decrementAgentLoad(oldAgentId);
                agentStateMapper.updateLoad(oldAgentId, redisService.getAgentLoad(oldAgentId));
            }
            sessionMapper.assignAgent(sessionId, supervisorId, "ACTIVE");
            redisService.incrementAgentLoad(supervisorId);
            redisService.bindSessionToAgent(sessionId, supervisorId);
            agentStateMapper.updateLoad(supervisorId, redisService.getAgentLoad(supervisorId));
            AllocationLog allocLog = new AllocationLog();
            allocLog.setSessionId(sessionId); allocLog.setAgentId(supervisorId); allocLog.setAction("TAKEOVER");
            allocLog.setReason(String.format("主管%d强制接管, 原客服%d", supervisorId, oldAgentId));
            allocationLogMapper.insert(allocLog);
            audit(String.valueOf(supervisorId), "SUPERVISOR", "SESSION_TAKEOVER", "SESSION", String.valueOf(sessionId), String.format("主管强制接管, 原客服=%d", oldAgentId));
            return sessionMapper.selectById(sessionId);
        } finally { redisService.unlock("session:" + sessionId); }
    }

    public List<Session> getAgentActiveSessions(long agentId) {
        return sessionMapper.selectByAgentIdAndStatus(agentId, "ACTIVE");
    }

    @Transactional
    public void agentOnline(long agentId) {
        Agent agent = requireAgent(agentId);
        agentMapper.updateStatus(agentId, "ONLINE");
        redisService.setAgentOnline(agentId, agent.getMaxCapacity());
        redisService.heartbeat(agentId);
        audit(String.valueOf(agentId), "AGENT", "AGENT_ONLINE", "AGENT", String.valueOf(agentId), "客服上线");
        messageQueue.publish(MessageQueue.Topics.AGENT_STATUS, String.format("{\"agentId\":%d,\"status\":\"ONLINE\"}", agentId));
    }

    @Transactional
    public void agentOffline(long agentId) {
        agentMapper.updateStatus(agentId, "OFFLINE");
        redisService.setAgentOffline(agentId);
        List<Session> activeSessions = sessionMapper.selectByAgentIdAndStatus(agentId, "ACTIVE");
        List<Session> assignedSessions = sessionMapper.selectByAgentIdAndStatus(agentId, "ASSIGNED");
        activeSessions.addAll(assignedSessions);
        for (Session s : activeSessions) {
            sessionMapper.updateStatus(s.getId(), "WAITING");
            redisService.unbindSessionFromAgent(s.getId(), agentId);
            queueService.rejoin(s);
            AllocationLog allocLog = new AllocationLog();
            allocLog.setSessionId(s.getId()); allocLog.setAgentId(agentId); allocLog.setAction("RELEASE"); allocLog.setReason("客服离线");
            allocationLogMapper.insert(allocLog);
        }
        agentStateMapper.updateLoad(agentId, 0);
        audit(String.valueOf(agentId), "AGENT", "AGENT_OFFLINE", "AGENT", String.valueOf(agentId), String.format("客服离线, 退回%d个会话", activeSessions.size()));
        messageQueue.publish(MessageQueue.Topics.AGENT_STATUS, String.format("{\"agentId\":%d,\"status\":\"OFFLINE\"}", agentId));
    }

    @Transactional
    public void agentBreak(long agentId) {
        agentMapper.updateStatus(agentId, "BREAK");
        redisService.setAgentBreak(agentId);
        messageQueue.publish(MessageQueue.Topics.AGENT_STATUS, String.format("{\"agentId\":%d,\"status\":\"BREAK\"}", agentId));
    }

    public Session getSession(long sessionId) { return requireSession(sessionId); }
    public Session getSessionByNo(String sessionNo) {
        Session s = sessionMapper.selectBySessionNo(sessionNo);
        if (s == null) throw new BizException("会话不存在");
        return s;
    }
    public List<Session> getCustomerActiveSessions(long customerId) {
        return sessionMapper.selectByCustomerIdAndStatus(customerId, "ACTIVE");
    }

    private Session requireSession(long sessionId) {
        Session s = sessionMapper.selectById(sessionId);
        if (s == null) throw new BizException("会话不存在: " + sessionId);
        return s;
    }
    private Agent requireAgent(long agentId) {
        Agent a = agentMapper.selectById(agentId);
        if (a == null) throw new BizException("客服不存在: " + agentId);
        return a;
    }
    private void requireStatus(Session session, String... allowed) {
        for (String s : allowed) if (s.equals(session.getStatus())) return;
        throw new BizException("会话状态不允许此操作, 当前: " + session.getStatus());
    }
    private String generateSessionNo() { return "CS" + System.currentTimeMillis() + UUID.randomUUID().toString().substring(0, 6).toUpperCase(); }
    private void audit(String operatorId, String operatorType, String action, String targetType, String targetId, String detail) {
        AuditLog log = new AuditLog();
        log.setOperatorId(operatorId); log.setOperatorType(operatorType); log.setAction(action);
        log.setTargetType(targetType); log.setTargetId(targetId); log.setDetail(detail);
        auditLogMapper.insert(log);
    }
}
