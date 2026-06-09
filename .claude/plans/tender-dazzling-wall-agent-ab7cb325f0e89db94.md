# Concurrency Bug Fix Implementation Plan
## Customer Service Session Allocation System (com.cs.alloc)

**Date:** 2026-06-09  
**Scope:** Fix race conditions causing duplicate session assignments during agent disconnect/reconnect, customer page refresh, and session transfer failures.

---

## Executive Summary

### Root Cause Analysis

After reading all 18 source files, 8 mapper XMLs, the schema, and configs, the concurrency bugs trace to **three systemic patterns**:

1. **Non-atomic check-then-act**: `createSession()`, `join()`, `acceptSession()` all read state then mutate without holding a lock across both operations.
2. **Lock scope too narrow**: Locks cover only the session ID, not the agent or the combined operation. Locks have no owner tracking, so any process can release any lock.
3. **Missing rollback/compensation**: `transferSession()` and `doAllocate()` perform multi-step operations (DB + Redis) without compensating transactions when a middle step fails.

### Fix Priority (Critical Path)

| Priority | Fix | Files | Risk if Unfixed |
|----------|-----|-------|-----------------|
| P0 | Redis lock owner tracking | RedisService.java | Any process can steal/unlock any lock |
| P0 | AcceptSession optimistic locking | SessionService.java, SessionMapper.xml | Two agents accept same session |
| P0 | Transfer rollback | SessionService.java | Sessions stuck in TRANSFERRING |
| P1 | Queue concurrent enqueue | QueueService.java, QueueEntryMapper.xml | Duplicate queue entries |
| P1 | Message idempotency gap | MessageService.java | Unhandled DuplicateKeyException |
| P1 | CreateSession race | SessionService.java | Two sessions for same customer |
| P1 | Agent WS reconnect atomicity | AgentWebSocketHandler.java, WsEventPusher.java | Ghost sessions, double assignment |
| P2 | Customer WS reconnect | CustomerWebSocketHandler.java, WsEventPusher.java | Orphan sessions on page refresh |
| P2 | Heartbeat consistency | AllocationEngine.java, RedisService.java | Online agents excluded from allocation |
| P2 | doAllocate compensation | AllocationEngine.java | DB-assigned but Redis-untracked sessions |


---

## Phase 1: Foundation — Redis Lock Hardening

### Problem
`RedisService.tryLock()` (line 164-166) uses `SET NX` with value `"1"` and `unlock()` (line 169-171) uses bare `DEL`. Two critical flaws:
- **No owner tracking**: If Process A holds the lock, Process B can call `unlock()` and delete it.
- **Lock expiry + re-acquire**: If Process A's lock expires (TTL), Process B acquires it, then Process A calls `unlock()` — it deletes Process B's lock.

### File: `src/main/java/com/cs/alloc/service/RedisService.java`

**Change 1a: Add lock owner tracking via UUID**

Replace `tryLock()` and `unlock()` (lines 164-171):

```java
// BEFORE (lines 164-171):
public boolean tryLock(String key, Duration ttl) {
    Boolean result = redis.opsForValue().setIfAbsent(KEY_LOCK + key, "1", ttl);
    return Boolean.TRUE.equals(result);
}

public void unlock(String key) {
    redis.delete(KEY_LOCK + key);
}

// AFTER:
public String tryLock(String key, Duration ttl) {
    String owner = UUID.randomUUID().toString();
    Boolean result = redis.opsForValue().setIfAbsent(KEY_LOCK + key, owner, ttl);
    return Boolean.TRUE.equals(result) ? owner : null;
}

public boolean unlock(String key, String owner) {
    if (owner == null) return false;
    // Lua script: only delete if the value matches our owner token
    String script = """
        if redis.call('get', KEYS[1]) == ARGV[1] then
            return redis.call('del', KEYS[1])
        else
            return 0
        end
        """;
    Long result = redis.execute(
        new DefaultRedisScript<>(script, Long.class),
        List.of(KEY_LOCK + key),
        owner
    );
    return result != null && result > 0;
}
```

**Required imports:** Add `java.util.UUID`, `java.util.List`, `org.springframework.data.redis.core.script.DefaultRedisScript`.

**Change 1b: Add `refreshLock()` for long operations**

```java
public boolean refreshLock(String key, String owner, Duration newTtl) {
    String script = """
        if redis.call('get', KEYS[1]) == ARGV[1] then
            return redis.call('pexpire', KEYS[1], ARGV[2])
        else
            return 0
        end
        """;
    Long result = redis.execute(
        new DefaultRedisScript<>(script, Long.class),
        List.of(KEY_LOCK + key),
        owner,
        String.valueOf(newTtl.toMillis())
    );
    return result != null && result > 0;
}
```

### Migration Impact

Every caller of `tryLock`/`unlock` must be updated. The affected call sites:
- `SessionService.acceptSession()` — line 56/70
- `SessionService.transferSession()` — line 77/103
- `SessionService.closeSession()` — line 128/140
- `SessionService.supervisorTakeover()` — line 149/167
- `AllocationEngine.doAllocate()` — line 113/141

Each changes from:
```java
if (!redisService.tryLock(key, ttl)) throw ...;
try { ... } finally { redisService.unlock(key); }
```
to:
```java
String lockOwner = redisService.tryLock(key, ttl);
if (lockOwner == null) throw ...;
try { ... } finally { redisService.unlock(key, lockOwner); }
```


---

## Phase 2: Session Creation Race Condition

### Problem
`SessionService.createSession()` (lines 29-48):
```java
redisService.getCustomerSession(customerId).ifPresent(existingId -> {
    Session existing = sessionMapper.selectById(existingId);
    if (existing != null && !existing.getStatus().equals("CLOSED")) {
        throw new BizException("...");
    }
});
// ... insert session ...
```
Two concurrent requests for the same customer can both pass the Redis check before either writes.

### File: `src/main/java/com/cs/alloc/service/SessionService.java`

**Change 2a: Add Redis lock + set customer session inside lock**

```java
@Transactional
public Session createSession(long customerId, long skillGroupId) {
    String lockOwner = redisService.tryLock("customer:session:" + customerId, Duration.ofSeconds(10));
    if (lockOwner == null) throw new BizException("正在处理您的请求，请稍后");
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
        // KEY CHANGE: Set Redis binding INSIDE the lock, BEFORE queue join
        redisService.setCustomerSession(customerId, session.getId());
        queueService.join(session, customer.getVipLevel());
        audit("SYSTEM", "SYSTEM", "SESSION_CREATE", "SESSION",
              String.valueOf(session.getId()),
              String.format("客户%d进入排队, 技能组%d", customerId, skillGroupId));
        return session;
    } finally {
        redisService.unlock("customer:session:" + customerId, lockOwner);
    }
}
```

Key: `redisService.setCustomerSession()` is now called INSIDE the lock, BEFORE queue join. The next concurrent request will see the binding and be rejected.

---

## Phase 3: Queue Service Concurrent Enqueue Protection

### Problem
`QueueService.join()` (lines 21-37): check-then-act is not atomic.
`QueueService.rejoin()` (lines 48-62): delete+insert not wrapped in a Redis lock.

### File: `src/main/java/com/cs/alloc/service/QueueService.java`

**Change 3a: Atomic join with Redis lock + INSERT IGNORE**

```java
@Transactional
public QueueEntry join(Session session, int vipLevel) {
    String lockOwner = redisService.tryLock("queue:session:" + session.getId(), Duration.ofSeconds(5));
    if (lockOwner == null) {
        // Another thread is joining; return existing or retry
        QueueEntry existing = queueEntryMapper.selectBySessionId(session.getId());
        return existing != null ? existing : retryJoin(session, vipLevel);
    }
    try {
        QueueEntry existing = queueEntryMapper.selectBySessionId(session.getId());
        if (existing != null) return existing; // idempotent
        int priorityScore = calculatePriority(vipLevel, LocalDateTime.now());
        int position = queueEntryMapper.countBySkillGroupId(session.getSkillGroupId()) + 1;
        QueueEntry entry = new QueueEntry();
        entry.setSessionId(session.getId());
        entry.setCustomerId(session.getCustomerId());
        entry.setSkillGroupId(session.getSkillGroupId());
        entry.setPriorityScore(priorityScore);
        entry.setPosition(position);
        entry.setJoinedAt(LocalDateTime.now());
        queueEntryMapper.insertIgnore(entry); // NEW: INSERT IGNORE at DB level
        QueueEntry result = queueEntryMapper.selectBySessionId(session.getId());
        redisService.addToQueue(session.getSkillGroupId(), session.getId(), result.getPriorityScore());
        log.info("客户入队: sessionId={}, position={}", session.getId(), result.getPosition());
        return result;
    } finally {
        redisService.unlock("queue:session:" + session.getId(), lockOwner);
    }
}
```

### File: `src/main/java/com/cs/alloc/mapper/QueueEntryMapper.java`

**Change 3b: Add `insertIgnore` method**

```java
void insertIgnore(QueueEntry entry);
```

### File: `src/main/resources/mapper/QueueEntryMapper.xml`

**Change 3c: Add INSERT IGNORE SQL**

Leverages existing `UNIQUE INDEX uk_queue_session (session_id)`:

```xml
<insert id="insertIgnore" useGeneratedKeys="true" keyProperty="id">
    INSERT IGNORE INTO queue_entry (session_id, customer_id, skill_group_id, priority_score, position)
    VALUES (#{sessionId}, #{customerId}, #{skillGroupId}, #{priorityScore}, #{position})
</insert>
```

### Change 3d: Transactional rejoin with lock

```java
@Transactional
public void rejoin(Session session) {
    String lockOwner = redisService.tryLock("queue:session:" + session.getId(), Duration.ofSeconds(5));
    if (lockOwner == null) { log.warn("rejoin: 无法获取锁, sessionId={}", session.getId()); return; }
    try {
        queueEntryMapper.deleteBySessionId(session.getId());
        redisService.removeFromQueue(session.getSkillGroupId(), session.getId());
        int position = queueEntryMapper.countBySkillGroupId(session.getSkillGroupId()) + 1;
        int priorityScore = session.getPriorityScore() != null ? session.getPriorityScore() : 0;
        QueueEntry entry = new QueueEntry();
        entry.setSessionId(session.getId());
        entry.setCustomerId(session.getCustomerId());
        entry.setSkillGroupId(session.getSkillGroupId());
        entry.setPriorityScore(priorityScore);
        entry.setPosition(position);
        entry.setJoinedAt(LocalDateTime.now());
        queueEntryMapper.insert(entry);
        redisService.addToQueue(session.getSkillGroupId(), session.getId(), priorityScore);
    } finally {
        redisService.unlock("queue:session:" + session.getId(), lockOwner);
    }
}
```


---

## Phase 4: Message Idempotency — Redis Expired + MySQL Written Edge Case

### Problem
`MessageService.sendMessage()` (lines 26-42): When the Redis idempotency key expires (10-min TTL) but the MySQL record exists (`UNIQUE INDEX uk_idempotency`), the flow is:
1. `trySetIdempotencyKey()` returns `true` (key was absent in Redis)
2. Code proceeds to `messageMapper.insert()`
3. MySQL throws `DuplicateKeyException`
4. Exception propagates unhandled — 500 error

### File: `src/main/java/com/cs/alloc/service/MessageService.java`

**Change 4a: Catch DuplicateKeyException and return existing message**

Add import: `org.springframework.dao.DuplicateKeyException`

```java
@Transactional
public Message sendMessage(long sessionId, String senderId, String senderType,
                           String content, String msgType, String idempotencyKey) {
    Session session = sessionMapper.selectById(sessionId);
    if (session == null) throw new BizException("会话不存在");
    if ("CLOSED".equals(session.getStatus())) throw new BizException("会话已结束");

    if (idempotencyKey != null && !idempotencyKey.isEmpty()) {
        if (!redisService.trySetIdempotencyKey(idempotencyKey, idempotencyKey, IDEMPOTENCY_TTL)) {
            Message existing = messageMapper.selectByIdempotencyKey(idempotencyKey);
            if (existing != null) {
                log.info("重复消息被拦截(Redis): key={}", idempotencyKey);
                return existing;
            }
        }
    }

    Message message = new Message();
    message.setSessionId(sessionId);
    message.setSenderId(senderId);
    message.setSenderType(senderType);
    message.setContent(content);
    message.setMsgType(msgType != null ? msgType : "TEXT");
    message.setIdempotencyKey(idempotencyKey);

    try {
        messageMapper.insert(message);
    } catch (DuplicateKeyException e) {
        // Redis key expired but MySQL UNIQUE index caught the duplicate
        log.info("重复消息被拦截(MySQL DuplicateKey): key={}", idempotencyKey);
        Message existing = messageMapper.selectByIdempotencyKey(idempotencyKey);
        if (existing != null) {
            // Re-set Redis key to prevent future DB hits
            if (idempotencyKey != null) {
                redisService.trySetIdempotencyKey(idempotencyKey, idempotencyKey, IDEMPOTENCY_TTL);
            }
            return existing;
        }
        throw new BizException("消息发送冲突，请重试");
    }

    messageQueue.publish(MessageQueue.Topics.CHAT_MESSAGE,
        String.format("{\"id\":%d,\"sessionId\":%d,\"senderId\":\"%s\"," +
            "\"senderType\":\"%s\",\"content\":\"%s\",\"msgType\":\"%s\"}",
            message.getId(), sessionId, senderId, senderType,
            content.replace("\"", "\\\"").replace("\n", "\n"),
            message.getMsgType()));
    return message;
}
```

---

## Phase 5: AcceptSession Atomicity

### Problem
`SessionService.acceptSession()` (lines 51-71):
- Lock only on session ID, not agent
- `assignAgent()` unconditionally sets agent_id — no optimistic check
- Two agents could race if lock expires

### File: `src/main/resources/mapper/SessionMapper.xml`

**Change 5a: Add optimistic assignAgent with status check**

```xml
<update id="assignAgentIfWaiting">
    UPDATE session
    SET agent_id = #{agentId}, status = #{status}, assigned_at = NOW()
    WHERE id = #{id} AND status = 'WAITING'
</update>
```

### File: `src/main/java/com/cs/alloc/mapper/SessionMapper.java`

**Change 5b: Add method signature returning affected rows**

```java
int assignAgentIfWaiting(@Param("id") Long id, @Param("agentId") Long agentId, @Param("status") String status);
```

### File: `src/main/java/com/cs/alloc/service/SessionService.java`

**Change 5c: Use optimistic locking in acceptSession**

```java
@Transactional
public Session acceptSession(long sessionId, long agentId) {
    Session session = requireSession(sessionId);
    requireStatus(session, "WAITING");
    Agent agent = requireAgent(agentId);
    if (!redisService.hasCapacity(agentId)) throw new BizException("客服已达最大接待量");

    String lockOwner = redisService.tryLock("session:" + sessionId, Duration.ofSeconds(10));
    if (lockOwner == null) throw new BizException("会话正在被分配");
    try {
        // Re-check status inside lock (DB is source of truth)
        Session fresh = sessionMapper.selectById(sessionId);
        if (fresh == null || !"WAITING".equals(fresh.getStatus())) {
            throw new BizException("会话已被其他客服接入");
        }

        // Optimistic assign: WHERE status = 'WAITING' — returns affected row count
        int affected = sessionMapper.assignAgentIfWaiting(sessionId, agentId, "ASSIGNED");
        if (affected == 0) {
            throw new BizException("会话已被其他客服接入");
        }

        redisService.incrementAgentLoad(agentId);
        redisService.bindSessionToAgent(sessionId, agentId);
        redisService.setCustomerSession(session.getCustomerId(), sessionId);
        agentStateMapper.updateLoad(agentId, redisService.getAgentLoad(agentId));
        queueService.leave(sessionId);

        AllocationLog allocLog = new AllocationLog();
        allocLog.setSessionId(sessionId); allocLog.setAgentId(agentId);
        allocLog.setAction("ALLOCATE"); allocLog.setReason("客服主动接入");
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
```


---

## Phase 6: Transfer Failure Rollback

### Problem
`SessionService.transferSession()` (lines 74-104):
- **Path A (direct to agent)**: If target goes offline between validation (line 85-86) and assignment (line 87), session stuck in TRANSFERRING.
- No compensation/rollback in either path.

### File: `src/main/java/com/cs/alloc/service/SessionService.java`

**Change 6a: Add rollback logic and pre-validation**

Inject `WsEventPusher`:
```java
private final com.cs.alloc.ws.WsEventPusher wsEventPusher;
```

```java
@Transactional
public Session transferSession(long sessionId, long fromAgentId, Long toAgentId,
                                Long toSkillGroupId, String reason) {
    Session session = requireSession(sessionId);
    requireStatus(session, "ASSIGNED", "ACTIVE");

    String lockOwner = redisService.tryLock("session:" + sessionId, Duration.ofSeconds(10));
    if (lockOwner == null) throw new BizException("会话正在操作中");
    try {
        long currentAgentId = session.getAgentId();

        if (toAgentId != null) {
            Agent toAgent = requireAgent(toAgentId);
            // Pre-validate target
            if (!redisService.isAgentAvailable(toAgentId))
                throw new BizException("目标客服不在线");
            if (!wsEventPusher.isAgentConnected(String.valueOf(toAgentId)))
                throw new BizException("目标客服连接不可用");
            if (!redisService.hasCapacity(toAgentId))
                throw new BizException("目标客服已满");

            // Release current agent
            sessionMapper.updateStatus(sessionId, "TRANSFERRING");
            redisService.unbindSessionFromAgent(sessionId, currentAgentId);
            redisService.decrementAgentLoad(currentAgentId);
            agentStateMapper.updateLoad(currentAgentId, redisService.getAgentLoad(currentAgentId));

            try {
                // Re-validate inside critical section
                if (!redisService.isAgentAvailable(toAgentId) || !redisService.hasCapacity(toAgentId))
                    throw new BizException("目标客服状态已变更");
                sessionMapper.assignAgent(sessionId, toAgentId, "ASSIGNED");
                redisService.incrementAgentLoad(toAgentId);
                redisService.bindSessionToAgent(sessionId, toAgentId);
                agentStateMapper.updateLoad(toAgentId, redisService.getAgentLoad(toAgentId));
            } catch (Exception e) {
                log.error("转接到客服{}失败, 执行回滚, sessionId={}", toAgentId, sessionId, e);
                rollbackTransfer(sessionId, currentAgentId, session.getSkillGroupId());
                throw new BizException("转接失败: " + e.getMessage() + "，会话已退回");
            }
        } else {
            // Path B: Transfer to queue
            long effectiveSkillGroupId = (toSkillGroupId != null) ? toSkillGroupId : session.getSkillGroupId();
            if (toSkillGroupId != null) {
                session.setSkillGroupId(toSkillGroupId);
                sessionMapper.updateSkillGroupId(sessionId, toSkillGroupId);
            }
            sessionMapper.updateStatus(sessionId, "WAITING");
            redisService.unbindSessionFromAgent(sessionId, currentAgentId);
            redisService.decrementAgentLoad(currentAgentId);
            agentStateMapper.updateLoad(currentAgentId, redisService.getAgentLoad(currentAgentId));
            session.setTransferFrom(currentAgentId);
            queueService.rejoin(session);
        }

        // ... logging and MQ publish (unchanged) ...
        AllocationLog allocLog = new AllocationLog();
        allocLog.setSessionId(sessionId); allocLog.setAgentId(currentAgentId);
        allocLog.setAction("TRANSFER"); allocLog.setReason(reason != null ? reason : "客服转接");
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

private void rollbackTransfer(long sessionId, long originalAgentId, long skillGroupId) {
    try {
        if (redisService.isAgentAvailable(originalAgentId) && redisService.hasCapacity(originalAgentId)) {
            sessionMapper.assignAgent(sessionId, originalAgentId, "ASSIGNED");
            redisService.incrementAgentLoad(originalAgentId);
            redisService.bindSessionToAgent(sessionId, originalAgentId);
            agentStateMapper.updateLoad(originalAgentId, redisService.getAgentLoad(originalAgentId));
            log.info("转接回滚: 重新分配给客服{}, sessionId={}", originalAgentId, sessionId);
        } else {
            sessionMapper.updateStatus(sessionId, "WAITING");
            Session s = sessionMapper.selectById(sessionId);
            queueService.rejoin(s);
            log.info("转接回滚: 退回排队, sessionId={}", sessionId);
        }
    } catch (Exception rollbackEx) {
        log.error("转接回滚也失败! sessionId={}, 强制设为WAITING", sessionId, rollbackEx);
        sessionMapper.updateStatus(sessionId, "WAITING");
        Session s = sessionMapper.selectById(sessionId);
        queueService.rejoin(s);
    }
}
```

### New Mapper Methods

**SessionMapper.java:**
```java
void updateSkillGroupId(@Param("id") Long id, @Param("skillGroupId") Long skillGroupId);
List<Session> selectByStatusAndOlderThan(@Param("status") String status, @Param("seconds") int seconds);
```

**SessionMapper.xml:**
```xml
<update id="updateSkillGroupId">
    UPDATE session SET skill_group_id = #{skillGroupId} WHERE id = #{id}
</update>
<select id="selectByStatusAndOlderThan" resultMap="BaseResultMap">
    SELECT * FROM session
    WHERE status = #{status} AND updated_at &lt; DATE_SUB(NOW(), INTERVAL #{seconds} SECOND)
</select>
```

### Stuck TRANSFERRING Cleanup

```java
@Scheduled(fixedDelay = 30000)
@Transactional
public void cleanupStuckTransfers() {
    List<Session> stuck = sessionMapper.selectByStatusAndOlderThan("TRANSFERRING", 30);
    for (Session s : stuck) {
        log.warn("清理卡住的转接会话: sessionId={}, 强制退回排队", s.getId());
        sessionMapper.updateStatus(s.getId(), "WAITING");
        queueService.rejoin(s);
        AllocationLog allocLog = new AllocationLog();
        allocLog.setSessionId(s.getId()); allocLog.setAction("RELEASE");
        allocLog.setReason("转接超时自动退回排队");
        allocationLogMapper.insert(allocLog);
    }
}
```


---

## Phase 7: WebSocket Session Recovery

### 7A: Agent WebSocket Reconnect Atomicity

### File: `src/main/java/com/cs/alloc/ws/AgentWebSocketHandler.java`

**Change 7a: Cancellable timer + atomic reconnect**

Replace fields and connection lifecycle methods:

```java
// NEW: Store ScheduledFuture for cancellable timers
private final Map<String, Instant> disconnectedAgents = new ConcurrentHashMap<>();
private final Map<String, ScheduledFuture<?>> reconnectTimers = new ConcurrentHashMap<>();
private final ScheduledExecutorService reconnectScheduler =
    Executors.newSingleThreadScheduledExecutor();

@Override
public void afterConnectionEstablished(WebSocketSession session) {
    String agentId = extractParam(session, "agentId");
    if (agentId == null) {
        try { session.close(CloseStatus.BAD_DATA); } catch (Exception ignored) {}
        return;
    }

    // ATOMIC: Cancel pending offline timer + remove from disconnected map
    boolean isReconnect = false;
    ScheduledFuture<?> pendingTimer = reconnectTimers.remove(agentId);
    if (pendingTimer != null) {
        pendingTimer.cancel(false);
        isReconnect = true;
    }
    disconnectedAgents.remove(agentId);

    pusher.registerAgent(agentId, session);

    // Restore heartbeat (may have expired during disconnect)
    redisService.heartbeat(Long.parseLong(agentId));

    // If Redis agent state lost, re-establish
    if (isReconnect && !redisService.isAgentAvailable(Long.parseLong(agentId))) {
        log.warn("Agent {} reconnecting but Redis state lost, re-establishing", agentId);
        try { sessionService.agentOnline(Long.parseLong(agentId)); }
        catch (Exception e) { log.error("Failed to re-establish agent {} online state", agentId, e); }
    }

    try {
        Map<String, Object> ackData = new HashMap<>();
        ackData.put("agentId", agentId);
        ackData.put("reconnect", isReconnect);
        if (isReconnect) {
            ackData.put("activeSessions",
                sessionService.getAgentActiveSessions(Long.parseLong(agentId)));
        }
        session.sendMessage(new TextMessage(MAPPER.writeValueAsString(
            Map.of("event", "connected", "data", ackData, "timestamp", System.currentTimeMillis()))));
    } catch (Exception ignored) {}
}

@Override
public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
    String agentId = extractParam(session, "agentId");
    if (agentId == null) return;
    pusher.unregisterAgent(agentId, session);
    if (status != CloseStatus.NORMAL) {
        disconnectedAgents.put(agentId, Instant.now());
        ScheduledFuture<?> future = reconnectScheduler.schedule(() -> {
            Instant dt = disconnectedAgents.get(agentId);
            if (dt != null
                && Duration.between(dt, Instant.now()).getSeconds() > 30
                && !pusher.isAgentConnected(agentId)) {
                disconnectedAgents.remove(agentId);
                reconnectTimers.remove(agentId);
                try { sessionService.agentOffline(Long.parseLong(agentId)); }
                catch (Exception e) { log.error("处理断线超时失败", e); }
            }
        }, 30, TimeUnit.SECONDS);
        reconnectTimers.put(agentId, future);
    }
}
```

### 7B: Customer WebSocket Reconnect

### File: `src/main/java/com/cs/alloc/ws/CustomerWebSocketHandler.java`

**Change 7b: Add customer reconnect + RedisService dependency**

```java
// Add RedisService to constructor:
public CustomerWebSocketHandler(WsEventPusher pusher, MessageService messageService,
                                 SessionService sessionService, QueueService queueService,
                                 RedisService redisService) {
    this.pusher = pusher; this.messageService = messageService;
    this.sessionService = sessionService; this.redisService = redisService;
}

@Override
public void afterConnectionEstablished(WebSocketSession session) {
    String customerId = extractParam(session, "customerId");
    if (customerId == null) {
        try { session.close(CloseStatus.BAD_DATA); } catch (Exception ignored) {}
        return;
    }

    // Replace old WS sessions (only one active connection per customer)
    pusher.replaceCustomerSession(customerId, session);

    try {
        Optional<Long> existingSessionId = redisService.getCustomerSession(Long.parseLong(customerId));
        Map<String, Object> ackData = new HashMap<>();
        ackData.put("customerId", customerId);

        if (existingSessionId.isPresent()) {
            Session existing = sessionService.getSession(existingSessionId.get());
            if (existing != null && !"CLOSED".equals(existing.getStatus())) {
                ackData.put("reconnect", true);
                ackData.put("sessionId", existing.getId());
                ackData.put("sessionNo", existing.getSessionNo());
                ackData.put("status", existing.getStatus());
                ackData.put("agentId", existing.getAgentId());
                if ("WAITING".equals(existing.getStatus())) {
                    ackData.put("queuePosition", sessionService.getQueuePosition(existingSessionId.get()));
                }
                log.info("Customer {} reconnected to session {}", customerId, existingSessionId.get());
            }
        }
        if (!ackData.containsKey("reconnect")) ackData.put("reconnect", false);

        session.sendMessage(new TextMessage(MAPPER.writeValueAsString(
            Map.of("event", "connected", "data", ackData, "timestamp", System.currentTimeMillis()))));
    } catch (Exception e) {
        log.error("Customer reconnect failed for {}", customerId, e);
    }
}

@Override
public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
    String customerId = extractParam(session, "customerId");
    if (customerId != null) pusher.unregisterCustomer(customerId, session);
    // NOTE: Do NOT clear Redis customer-session binding. Session remains active for reconnect.
}
```

### 7C: WsEventPusher Enhancements

### File: `src/main/java/com/cs/alloc/ws/WsEventPusher.java`

**Change 7c: Add replaceCustomerSession + isCustomerConnected**

```java
public void replaceCustomerSession(String customerId, WebSocketSession session) {
    Set<WebSocketSession> newSet = new CopyOnWriteArraySet<>();
    newSet.add(session);
    Set<WebSocketSession> old = customerSessions.put(customerId, newSet);
    if (old != null) {
        for (WebSocketSession ws : old) {
            if (ws.isOpen() && !ws.equals(session)) {
                try {
                    ws.sendMessage(new TextMessage(MAPPER.writeValueAsString(
                        Map.of("event", "session.replaced",
                               "data", Map.of("reason", "新连接已建立"),
                               "timestamp", System.currentTimeMillis()))));
                    ws.close(CloseStatus.NORMAL);
                } catch (IOException e) { log.warn("关闭旧客户WS失败: {}", e.getMessage()); }
            }
        }
    }
}

public boolean isCustomerConnected(String customerId) {
    Set<WebSocketSession> set = customerSessions.get(customerId);
    return set != null && !set.isEmpty() && set.stream().anyMatch(WebSocketSession::isOpen);
}
```


---

## Phase 8: Agent Heartbeat Enhancement

### File: `src/main/java/com/cs/alloc/ws/AgentWebSocketHandler.java`

**Change 8a: Renew heartbeat on EVERY WS message**

In `handleTextMessage()`, add at the top before the switch:

```java
@Override
protected void handleTextMessage(WebSocketSession session, TextMessage message) {
    String agentId = extractParam(session, "agentId");
    if (agentId == null) return;
    // KEY CHANGE: Renew heartbeat on ANY message
    redisService.heartbeat(Long.parseLong(agentId));
    try {
        // ... existing switch logic unchanged ...
    } catch (Exception e) { log.error("处理客服消息失败", e); }
}
```

### File: `src/main/java/com/cs/alloc/service/AllocationEngine.java`

**Change 8b: Cross-check heartbeat + WS in loadCandidates**

Inject `WsEventPusher` via constructor.

```java
private List<AgentCandidate> loadCandidates(long skillGroupId) {
    List<Agent> onlineAgents = agentMapper.selectBySkillGroupId(skillGroupId);
    List<AgentCandidate> candidates = new ArrayList<>();
    for (Agent agent : onlineAgents) {
        if (!redisService.isAgentAvailable(agent.getId())) continue;
        if (!redisService.hasCapacity(agent.getId())) continue;

        // Heartbeat + WS cross-check
        Optional<Instant> lastHb = redisService.getLastHeartbeat(agent.getId());
        if (lastHb.isEmpty()) {
            boolean wsAlive = wsEventPusher.isAgentConnected(String.valueOf(agent.getId()));
            if (!wsAlive) {
                log.warn("Agent {} heartbeat expired + no WS, skipping", agent.getId());
                continue;
            } else {
                log.info("Agent {} heartbeat expired but WS alive, renewing", agent.getId());
                redisService.heartbeat(agent.getId());
            }
        }

        AgentState state = agentStateMapper.selectByAgentId(agent.getId());
        int load = state != null ? state.getCurrentLoad() : 0;
        candidates.add(new AgentCandidate(agent, load));
    }
    return candidates;
}
```

### File: `src/main/java/com/cs/alloc/service/SessionService.java`

**Change 8c: Scheduled heartbeat consistency checker**

```java
@Scheduled(fixedDelay = 60000)
public void checkHeartbeatConsistency() {
    Set<String> onlineAgentIds = redisService.scanOnlineAgentIds();
    for (String agentIdStr : onlineAgentIds) {
        long agentId = Long.parseLong(agentIdStr);
        Optional<Instant> lastHb = redisService.getLastHeartbeat(agentId);
        if (lastHb.isEmpty()) {
            boolean wsAlive = wsEventPusher.isAgentConnected(agentIdStr);
            if (!wsAlive) {
                log.warn("Agent {} heartbeat expired + no WS, forcing offline", agentId);
                try { agentOffline(agentId); }
                catch (Exception e) { log.error("Failed to offline stale agent {}", agentId, e); }
            } else {
                redisService.heartbeat(agentId);
            }
        }
    }
}
```

### File: `src/main/java/com/cs/alloc/service/RedisService.java`

**Change 8d: Add scanOnlineAgentIds()**

```java
public Set<String> scanOnlineAgentIds() {
    Set<String> onlineAgents = new HashSet<>();
    try (var cursor = redis.scan(
            ScanOptions.scanOptions().match(KEY_AGENT_STATE + "*").count(100).build())) {
        while (cursor.hasNext()) {
            String key = cursor.next();
            Object status = redis.opsForHash().get(key, "status");
            if ("ONLINE".equals(String.valueOf(status))) {
                onlineAgents.add(key.substring(KEY_AGENT_STATE.length()));
            }
        }
    }
    return onlineAgents;
}
```

---

## Phase 9: Allocation Engine Hardening

### File: `src/main/java/com/cs/alloc/service/AllocationEngine.java`

**Change 9a: Compensation for Redis bind failure in doAllocate**

```java
@Transactional
void doAllocate(QueueEntry qe, AgentCandidate candidate) {
    long sessionId = qe.getSessionId();
    long agentId = candidate.agent.getId();
    String lockKey = "session:" + sessionId;
    String lockOwner = redisService.tryLock(lockKey, java.time.Duration.ofSeconds(10));
    if (lockOwner == null) { log.warn("分配锁失败, sessionId={}", sessionId); return; }
    try {
        Session session = sessionMapper.selectById(sessionId);
        if (session == null || !"WAITING".equals(session.getStatus())) return;

        int affected = sessionMapper.assignAgentIfWaiting(sessionId, agentId, "ASSIGNED");
        if (affected == 0) { log.info("会话{}已被分配, 跳过", sessionId); return; }

        try {
            redisService.incrementAgentLoad(agentId);
            redisService.bindSessionToAgent(sessionId, agentId);
            agentStateMapper.updateLoad(agentId, redisService.getAgentLoad(agentId));
            redisService.setCustomerSession(qe.getCustomerId(), sessionId);
            queueService.leave(sessionId);
        } catch (Exception redisEx) {
            // COMPENSATION: Redis bind failed after DB assign — rollback
            log.error("Redis绑定失败, 回滚DB分配, sessionId={}", sessionId, redisEx);
            sessionMapper.updateStatus(sessionId, "WAITING");
            queueService.rejoin(session);
            return;
        }

        // ... allocation log + MQ publish (unchanged) ...
    } finally {
        redisService.unlock(lockKey, lockOwner);
    }
}
```

test
---

## Test Plan

### QueueServiceTest.java - New Tests

Tests to add:
1. `concurrentJoinSameSession` - verify INSERT IGNORE called under lock
2. `duplicateJoinPreservesPosition` - verify existing entry returned without insert
3. `rejoinUnderLock` - verify lock acquired and released around rejoin

### SessionServiceTest.java - New Tests

Tests to add:
1. `acceptSessionOptimisticLock` - verify assignAgentIfWaiting called
2. `acceptSessionOptimisticLockFailure` - verify BizException when another agent wins
3. `transferToOfflineAgentRejected` - verify offline target rejected before mutation
4. `createSessionConcurrentProtection` - verify setCustomerSession inside lock before join

### MessageServiceTest.java - New Test

Tests to add:
1. `redisExpiredMysqlDuplicateReturnsExisting` - verify DuplicateKey caught and existing returned

### New: AgentWebSocketHandlerTest.java

Tests: reconnect cancels timer, reconnect re-establishes Redis state, normal close skips reconnect

### New: CustomerWebSocketHandlerTest.java

Tests: reconnect binds existing session, disconnect preserves Redis binding, WAITING returns queue position

---

## Implementation Order and Dependencies

```
Phase 1: Redis Lock Hardening (RedisService.java)
    |
    +--> Phase 2: CreateSession Race [depends on Phase 1]
    +--> Phase 3: Queue Concurrent Enqueue [depends on Phase 1]
    +--> Phase 4: Message Idempotency [INDEPENDENT]
    +--> Phase 5: AcceptSession Atomicity [depends on Phase 1]
    +--> Phase 6: Transfer Rollback [depends on Phase 1 + Phase 7c]
    +--> Phase 7: WebSocket Recovery [7a,7b independent; 7c needed by 6,8]
    +--> Phase 8: Heartbeat Enhancement [depends on Phase 7c]
    +--> Phase 9: Allocation Engine [depends on Phase 1 + Phase 5b]
```

### Recommended Sequence

| Step | Phase(s) | Effort | Rationale |
|------|----------|--------|-----------|
| 1 | Phase 1 + Phase 4 | 2h | Foundation + quick independent fix |
| 2 | Phase 5 + Phase 9 | 3h | Both use assignAgentIfWaiting |
| 3 | Phase 2 + Phase 3 | 2h | Both lock-based concurrency fixes |
| 4 | Phase 7 (all) | 4h | Largest change: WS handlers + WsEventPusher |
| 5 | Phase 6 | 3h | Depends on Phase 7c |
| 6 | Phase 8 | 2h | Depends on Phase 7c |
| 7 | All tests | 3h | Write alongside each phase |
| **Total** | | **~19h** | |

### Schema Changes Required

**No DDL changes needed.** Existing indexes support all fixes:
- `UNIQUE INDEX uk_queue_session` on `queue_entry` supports INSERT IGNORE
- `UNIQUE INDEX uk_idempotency` on `message` supports DuplicateKeyException
- `updated_at ON UPDATE CURRENT_TIMESTAMP` on `session` supports stuck transfer cleanup

### Files Modified Summary

| File | Phase | Type |
|------|-------|------|
| `RedisService.java` | 1, 8d | Core service |
| `SessionService.java` | 2, 5c, 6a, 7d, 8c | Core service |
| `QueueService.java` | 3a, 3d | Core service |
| `MessageService.java` | 4a | Core service |
| `AllocationEngine.java` | 8b, 9a | Core service |
| `AgentWebSocketHandler.java` | 7a, 8a | WS handler |
| `CustomerWebSocketHandler.java` | 7b | WS handler |
| `WsEventPusher.java` | 7c | WS handler |
| `SessionMapper.java` | 5b, 6b | Mapper interface |
| `SessionMapper.xml` | 5a, 6c | MyBatis XML |
| `QueueEntryMapper.java` | 3b | Mapper interface |
| `QueueEntryMapper.xml` | 3c | MyBatis XML |
| `SessionServiceTest.java` | Tests | Test (modified) |
| `QueueServiceTest.java` | Tests | Test (modified) |
| `MessageServiceTest.java` | Tests | Test (modified) |
| `AgentWebSocketHandlerTest.java` | Tests | Test (NEW) |
| `CustomerWebSocketHandlerTest.java` | Tests | Test (NEW) |
