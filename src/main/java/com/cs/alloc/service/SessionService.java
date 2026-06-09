package com.cs.alloc.service;

import com.cs.alloc.common.BizException;
import com.cs.alloc.domain.*;
import com.cs.alloc.mapper.*;
import com.cs.alloc.ws.WsEventPusher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
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
    private final WsEventPusher wsEventPusher;

    /**
     * 创建会话: 加 Redis 锁防止同一客户并发创建, 锁内绑定 customer→session。
     */
    @Transactional
    public Session createSession(long customerId, long skillGroupId) {
        String lockOwner = redisService.tryLock("customer:session:" + customerId, Duration.ofSeconds(5));
        if (lockOwner == null) throw new BizException("请求处理中, 请勿重复操作");
        try {
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
            // 锁内立即绑定 customer→session, 防止并发窗口
            redisService.setCustomerSession(customerId, session.getId());
            queueService.join(session, customer.getVipLevel());
            audit("SYSTEM", "SYSTEM", "SESSION_CREATE", "SESSION", String.valueOf(session.getId()),
                    String.format("客户%d进入排队, 技能组%d", customerId, skillGroupId));
            return session;
        } finally {
            redisService.unlock("customer:session:" + customerId, lockOwner);
        }
    }

    /**
     * 客服主动接入: 乐观锁 WHERE status='WAITING' 防止双接入。
     */
    @Transactional
    public Session acceptSession(long sessionId, long agentId) {
        Agent agent = requireAgent(agentId);
        if (!redisService.hasCapacity(agentId)) throw new BizException("客服已达最大接待量");
        String lockOwner = redisService.tryLock("session:" + sessionId, Duration.ofSeconds(10));
        if (lockOwner == null) throw new BizException("会话正在被分配");
        try {
            // 乐观锁: 只有 status=WAITING 时才能 assign, 返回受影响行数
            int rows = sessionMapper.assignAgentIfWaiting(sessionId, agentId);
            if (rows == 0) throw new BizException("会话已被其他客服接入或状态已变更");

            Session session = requireSession(sessionId);
            redisService.incrementAgentLoad(agentId);
            redisService.bindSessionToAgent(sessionId, agentId);
            redisService.setCustomerSession(session.getCustomerId(), sessionId);
            agentStateMapper.updateLoad(agentId, redisService.getAgentLoad(agentId));
            queueService.leave(sessionId);

            AllocationLog allocLog = new AllocationLog();
            allocLog.setSessionId(sessionId);
            allocLog.setAgentId(agentId);
            allocLog.setAction("ALLOCATE");
            allocLog.setReason("客服主动接入");
            allocationLogMapper.insert(allocLog);

            audit(String.valueOf(agentId), "AGENT", "SESSION_ACCEPT", "SESSION",
                    String.valueOf(sessionId), "客服主动接入");
            messageQueue.publish(MessageQueue.Topics.SESSION_ALLOCATED,
                    String.format("{\"sessionId\":%d,\"agentId\":%d,\"customerId\":%d}",
                            sessionId, agentId, session.getCustomerId()));
            return sessionMapper.selectById(sessionId);
        } finally {
            redisService.unlock("session:" + sessionId, lockOwner);
        }
    }

    /**
     * 转接: 先验证目标客服, 失败时回滚到原客服。
     */
    @Transactional
    public Session transferSession(long sessionId, long fromAgentId, Long toAgentId,
                                   Long toSkillGroupId, String reason) {
        Session session = requireSession(sessionId);
        requireStatus(session, "ASSIGNED", "ACTIVE");
        String lockOwner = redisService.tryLock("session:" + sessionId, Duration.ofSeconds(10));
        if (lockOwner == null) throw new BizException("会话正在操作中");
        try {
            long currentAgentId = session.getAgentId();

            // 1. 预验证目标客服 (在释放原客服之前)
            if (toAgentId != null) {
                Agent toAgent = requireAgent(toAgentId);
                if (!redisService.isAgentAvailable(toAgentId)) {
                    throw new BizException("目标客服不在线");
                }
                if (!redisService.hasCapacity(toAgentId)) {
                    throw new BizException("目标客服已达最大接待量");
                }
            }

            // 2. 释放原客服
            sessionMapper.updateStatus(sessionId, "TRANSFERRING");
            redisService.unbindSessionFromAgent(sessionId, currentAgentId);
            redisService.decrementAgentLoad(currentAgentId);
            agentStateMapper.updateLoad(currentAgentId, redisService.getAgentLoad(currentAgentId));

            // 3. 分配目标 (失败则回滚)
            try {
                if (toAgentId != null) {
                    sessionMapper.assignAgent(sessionId, toAgentId, "ASSIGNED");
                    redisService.incrementAgentLoad(toAgentId);
                    redisService.bindSessionToAgent(sessionId, toAgentId);
                    agentStateMapper.updateLoad(toAgentId, redisService.getAgentLoad(toAgentId));
                } else {
                    if (toSkillGroupId != null) {
                        session.setSkillGroupId(toSkillGroupId);
                        sessionMapper.updateSkillGroupId(sessionId, toSkillGroupId);
                    }
                    session.setTransferFrom(currentAgentId);
                    sessionMapper.updateStatus(sessionId, "WAITING");
                    queueService.rejoin(session);
                }
            } catch (Exception e) {
                // 回滚: 恢复到原客服
                log.error("转接目标分配失败, 回滚到原客服: sessionId={}, error={}", sessionId, e.getMessage());
                sessionMapper.assignAgent(sessionId, currentAgentId, "ACTIVE");
                redisService.incrementAgentLoad(currentAgentId);
                redisService.bindSessionToAgent(sessionId, currentAgentId);
                agentStateMapper.updateLoad(currentAgentId, redisService.getAgentLoad(currentAgentId));
                throw new BizException("转接失败, 会话已恢复到原客服: " + e.getMessage());
            }

            AllocationLog allocLog = new AllocationLog();
            allocLog.setSessionId(sessionId);
            allocLog.setAgentId(currentAgentId);
            allocLog.setAction("TRANSFER");
            allocLog.setReason(reason != null ? reason : "客服转接");
            allocationLogMapper.insert(allocLog);

            audit(String.valueOf(fromAgentId), "AGENT", "SESSION_TRANSFER", "SESSION",
                    String.valueOf(sessionId), String.format("从客服%d转接", currentAgentId));
            messageQueue.publish(MessageQueue.Topics.SESSION_TRANSFERRED,
                    String.format("{\"sessionId\":%d,\"fromAgent\":%d,\"toAgent\":%s}",
                            sessionId, currentAgentId, toAgentId != null ? toAgentId : "null"));
            return sessionMapper.selectById(sessionId);
        } finally {
            redisService.unlock("session:" + sessionId, lockOwner);
        }
    }

    @Transactional
    public Session suspendSession(long sessionId, long agentId) {
        Session session = requireSession(sessionId);
        requireStatus(session, "ASSIGNED", "ACTIVE");
        sessionMapper.updateStatus(sessionId, "SUSPENDED");
        audit(String.valueOf(agentId), "AGENT", "SESSION_SUSPEND", "SESSION",
                String.valueOf(sessionId), "客服挂起");
        return sessionMapper.selectById(sessionId);
    }

    @Transactional
    public Session resumeSession(long sessionId, long agentId) {
        Session session = requireSession(sessionId);
        requireStatus(session, "SUSPENDED");
        sessionMapper.updateStatus(sessionId, "ACTIVE");
        audit(String.valueOf(agentId), "AGENT", "SESSION_RESUME", "SESSION",
                String.valueOf(sessionId), "客服恢复");
        return sessionMapper.selectById(sessionId);
    }

    @Transactional
    public void closeSession(long sessionId, String operatorId, String operatorType, String reason) {
        Session session = requireSession(sessionId);
        if ("CLOSED".equals(session.getStatus())) return;
        String lockOwner = redisService.tryLock("session:" + sessionId, Duration.ofSeconds(10));
        if (lockOwner == null) throw new BizException("会话正在操作中");
        try {
            sessionMapper.close(sessionId);
            if (session.getAgentId() != null) {
                redisService.unbindSessionFromAgent(sessionId, session.getAgentId());
                redisService.decrementAgentLoad(session.getAgentId());
                agentStateMapper.updateLoad(session.getAgentId(),
                        redisService.getAgentLoad(session.getAgentId()));
            }
            redisService.clearCustomerSession(session.getCustomerId());
            queueService.leave(sessionId);
            audit(operatorId, operatorType, "SESSION_CLOSE", "SESSION",
                    String.valueOf(sessionId), reason != null ? reason : "会话结束");
            messageQueue.publish(MessageQueue.Topics.SESSION_CLOSED,
                    String.format("{\"sessionId\":%d,\"customerId\":%d}",
                            sessionId, session.getCustomerId()));
        } finally {
            redisService.unlock("session:" + sessionId, lockOwner);
        }
    }

    @Transactional
    public Session supervisorTakeover(long sessionId, long supervisorId) {
        Session session = requireSession(sessionId);
        requireStatus(session, "ASSIGNED", "ACTIVE", "SUSPENDED");
        Agent supervisor = requireAgent(supervisorId);
        if (!Boolean.TRUE.equals(supervisor.getIsSupervisor())) throw new BizException("仅主管可执行接管操作");
        String lockOwner = redisService.tryLock("session:" + sessionId, Duration.ofSeconds(10));
        if (lockOwner == null) throw new BizException("会话正在操作中");
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
            allocLog.setSessionId(sessionId);
            allocLog.setAgentId(supervisorId);
            allocLog.setAction("TAKEOVER");
            allocLog.setReason(String.format("主管%d强制接管, 原客服%d", supervisorId, oldAgentId));
            allocationLogMapper.insert(allocLog);
            audit(String.valueOf(supervisorId), "SUPERVISOR", "SESSION_TAKEOVER", "SESSION",
                    String.valueOf(sessionId),
                    String.format("主管强制接管, 原客服=%d", oldAgentId));
            return sessionMapper.selectById(sessionId);
        } finally {
            redisService.unlock("session:" + sessionId, lockOwner);
        }
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
        audit(String.valueOf(agentId), "AGENT", "AGENT_ONLINE", "AGENT",
                String.valueOf(agentId), "客服上线");
        messageQueue.publish(MessageQueue.Topics.AGENT_STATUS,
                String.format("{\"agentId\":%d,\"status\":\"ONLINE\"}", agentId));
    }

    /**
     * 客服离线: 加锁防止与 AllocationEngine 并发操作同一客服的会话。
     */
    @Transactional
    public void agentOffline(long agentId) {
        String lockOwner = redisService.tryLock("agent:offline:" + agentId, Duration.ofSeconds(30));
        if (lockOwner == null) {
            log.warn("客服 {} 正在执行离线处理, 跳过", agentId);
            return;
        }
        try {
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
                allocLog.setSessionId(s.getId());
                allocLog.setAgentId(agentId);
                allocLog.setAction("RELEASE");
                allocLog.setReason("客服离线");
                allocationLogMapper.insert(allocLog);
            }
            agentStateMapper.updateLoad(agentId, 0);
            audit(String.valueOf(agentId), "AGENT", "AGENT_OFFLINE", "AGENT",
                    String.valueOf(agentId),
                    String.format("客服离线, 退回%d个会话", activeSessions.size()));
            messageQueue.publish(MessageQueue.Topics.AGENT_STATUS,
                    String.format("{\"agentId\":%d,\"status\":\"OFFLINE\"}", agentId));
        } finally {
            redisService.unlock("agent:offline:" + agentId, lockOwner);
        }
    }

    @Transactional
    public void agentBreak(long agentId) {
        agentMapper.updateStatus(agentId, "BREAK");
        redisService.setAgentBreak(agentId);
        messageQueue.publish(MessageQueue.Topics.AGENT_STATUS,
                String.format("{\"agentId\":%d,\"status\":\"BREAK\"}", agentId));
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

    /**
     * 定时清理卡在 TRANSFERRING 状态超过1分钟的会话。
     */
    @Scheduled(fixedDelay = 60000)
    @Transactional
    public void cleanupStuckTransfers() {
        List<Session> stuck = sessionMapper.selectStuckTransferring(1);
        for (Session s : stuck) {
            log.warn("清理卡死转接会话: sessionId={}, skillGroupId={}", s.getId(), s.getSkillGroupId());
            sessionMapper.updateStatus(s.getId(), "WAITING");
            queueService.rejoin(s);
            AllocationLog allocLog = new AllocationLog();
            allocLog.setSessionId(s.getId());
            allocLog.setAction("RELEASE");
            allocLog.setReason("转接超时自动清理");
            allocationLogMapper.insert(allocLog);
        }
        if (!stuck.isEmpty()) {
            log.info("清理了 {} 个卡死转接会话", stuck.size());
        }
    }

    /**
     * 心跳一致性检查: 交叉验证 Redis 心跳键 + WS 连接状态。
     */
    @Scheduled(fixedDelay = 60000)
    @Transactional
    public void checkHeartbeatConsistency() {
        List<Agent> onlineAgents = agentMapper.selectAllOnline();
        for (Agent agent : onlineAgents) {
            boolean heartbeatAlive = redisService.isHeartbeatAlive(agent.getId());
            boolean wsConnected = wsEventPusher.isAgentConnected(String.valueOf(agent.getId()));
            if (!heartbeatAlive && !wsConnected) {
                log.warn("客服 {} 心跳过期且无WS连接, 执行离线处理", agent.getId());
                agentOffline(agent.getId());
            } else if (!heartbeatAlive && wsConnected) {
                log.info("客服 {} 心跳过期但WS在线, 刷新心跳", agent.getId());
                redisService.heartbeat(agent.getId());
            }
        }
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
    private String generateSessionNo() {
        return "CS" + System.currentTimeMillis() + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    }
    private void audit(String operatorId, String operatorType, String action,
                       String targetType, String targetId, String detail) {
        AuditLog log = new AuditLog();
        log.setOperatorId(operatorId);
        log.setOperatorType(operatorType);
        log.setAction(action);
        log.setTargetType(targetType);
        log.setTargetId(targetId);
        log.setDetail(detail);
        auditLogMapper.insert(log);
    }
}
