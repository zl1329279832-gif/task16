package com.cs.alloc.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
public class RedisService {
    private static final String KEY_AGENT_STATE   = "cs:agent:state:";
    private static final String KEY_AGENT_SESSIONS = "cs:agent:sessions:";
    private static final String KEY_SESSION_AGENT  = "cs:session:agent:";
    private static final String KEY_CUSTOMER_SESSION = "cs:customer:session:";
    private static final String KEY_QUEUE_SET      = "cs:queue:";
    private static final String KEY_IDEMPOTENCY    = "cs:idempotency:";
    private static final String KEY_LOCK           = "cs:lock:";
    private static final String KEY_HEARTBEAT      = "cs:agent:heartbeat:";

    private final StringRedisTemplate redis;

    public RedisService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void setAgentOnline(long agentId, int maxCapacity) {
        Map<String, String> state = Map.of("status", "ONLINE", "maxCapacity", String.valueOf(maxCapacity), "currentLoad", "0", "since", Instant.now().toString());
        redis.opsForHash().putAll(KEY_AGENT_STATE + agentId, state);
    }

    public void setAgentOffline(long agentId) {
        redis.opsForHash().put(KEY_AGENT_STATE + agentId, "status", "OFFLINE");
    }

    public void setAgentBreak(long agentId) {
        redis.opsForHash().put(KEY_AGENT_STATE + agentId, "status", "BREAK");
    }

    public Map<Object, Object> getAgentState(long agentId) {
        return redis.opsForHash().entries(KEY_AGENT_STATE + agentId);
    }

    public boolean isAgentAvailable(long agentId) {
        Map<Object, Object> state = getAgentState(agentId);
        return "ONLINE".equals(String.valueOf(state.get("status")));
    }

    public int getAgentLoad(long agentId) {
        Map<Object, Object> state = getAgentState(agentId);
        Object load = state.get("currentLoad");
        return load != null ? Integer.parseInt(String.valueOf(load)) : 0;
    }

    public int getAgentMaxCapacity(long agentId) {
        Map<Object, Object> state = getAgentState(agentId);
        Object cap = state.get("maxCapacity");
        return cap != null ? Integer.parseInt(String.valueOf(cap)) : 0;
    }

    public long incrementAgentLoad(long agentId) {
        return redis.opsForHash().increment(KEY_AGENT_STATE + agentId, "currentLoad", 1);
    }

    public long decrementAgentLoad(long agentId) {
        long val = redis.opsForHash().increment(KEY_AGENT_STATE + agentId, "currentLoad", -1);
        return Math.max(val, 0);
    }

    public boolean hasCapacity(long agentId) {
        return getAgentLoad(agentId) < getAgentMaxCapacity(agentId);
    }

    public void bindSessionToAgent(long sessionId, long agentId) {
        redis.opsForSet().add(KEY_AGENT_SESSIONS + agentId, String.valueOf(sessionId));
        redis.opsForValue().set(KEY_SESSION_AGENT + sessionId, String.valueOf(agentId));
    }

    public void unbindSessionFromAgent(long sessionId, long agentId) {
        redis.opsForSet().remove(KEY_AGENT_SESSIONS + agentId, String.valueOf(sessionId));
        redis.delete(KEY_SESSION_AGENT + sessionId);
    }

    public Set<String> getAgentSessionIds(long agentId) {
        return redis.opsForSet().members(KEY_AGENT_SESSIONS + agentId);
    }

    public Optional<Long> getSessionAgentId(long sessionId) {
        String val = redis.opsForValue().get(KEY_SESSION_AGENT + sessionId);
        return val != null ? Optional.of(Long.parseLong(val)) : Optional.empty();
    }

    public void setCustomerSession(long customerId, long sessionId) {
        redis.opsForValue().set(KEY_CUSTOMER_SESSION + customerId, String.valueOf(sessionId));
    }

    public void clearCustomerSession(long customerId) {
        redis.delete(KEY_CUSTOMER_SESSION + customerId);
    }

    public Optional<Long> getCustomerSession(long customerId) {
        String val = redis.opsForValue().get(KEY_CUSTOMER_SESSION + customerId);
        return val != null ? Optional.of(Long.parseLong(val)) : Optional.empty();
    }

    public void addToQueue(long skillGroupId, long sessionId, double priorityScore) {
        redis.opsForZSet().add(KEY_QUEUE_SET + skillGroupId, String.valueOf(sessionId), priorityScore);
    }

    public void removeFromQueue(long skillGroupId, long sessionId) {
        redis.opsForZSet().remove(KEY_QUEUE_SET + skillGroupId, String.valueOf(sessionId));
    }

    public Optional<Long> pollFromQueue(long skillGroupId) {
        Set<String> top = redis.opsForZSet().range(KEY_QUEUE_SET + skillGroupId, 0, 0);
        if (top != null && !top.isEmpty()) {
            String sessionId = top.iterator().next();
            redis.opsForZSet().remove(KEY_QUEUE_SET + skillGroupId, sessionId);
            return Optional.of(Long.parseLong(sessionId));
        }
        return Optional.empty();
    }

    public Set<String> getQueueMembers(long skillGroupId) {
        return redis.opsForZSet().range(KEY_QUEUE_SET + skillGroupId, 0, -1);
    }

    public long getQueueSize(long skillGroupId) {
        Long size = redis.opsForZSet().size(KEY_QUEUE_SET + skillGroupId);
        return size != null ? size : 0;
    }

    public long getQueuePosition(long skillGroupId, long sessionId) {
        Long rank = redis.opsForZSet().reverseRank(KEY_QUEUE_SET + skillGroupId, String.valueOf(sessionId));
        return rank != null ? rank : -1;
    }

    public void updateQueuePriority(long skillGroupId, long sessionId, double newScore) {
        redis.opsForZSet().add(KEY_QUEUE_SET + skillGroupId, String.valueOf(sessionId), newScore);
    }

    public boolean trySetIdempotencyKey(String key, String messageId, Duration ttl) {
        Boolean result = redis.opsForValue().setIfAbsent(KEY_IDEMPOTENCY + key, messageId, ttl);
        return Boolean.TRUE.equals(result);
    }

    public Optional<String> getIdempotencyKey(String key) {
        String val = redis.opsForValue().get(KEY_IDEMPOTENCY + key);
        return Optional.ofNullable(val);
    }

    public void heartbeat(long agentId) {
        redis.opsForValue().set(KEY_HEARTBEAT + agentId, Instant.now().toString(), Duration.ofMinutes(2));
    }

    public Optional<Instant> getLastHeartbeat(long agentId) {
        String val = redis.opsForValue().get(KEY_HEARTBEAT + agentId);
        return val != null ? Optional.of(Instant.parse(val)) : Optional.empty();
    }

    public boolean isHeartbeatAlive(long agentId) {
        return Boolean.TRUE.equals(redis.hasKey(KEY_HEARTBEAT + agentId));
    }

    /**
     * 尝试获取分布式锁, 返回 owner token (UUID)。
     * 返回 null 表示锁已被其他持有者占用。
     */
    public String tryLock(String key, Duration ttl) {
        String owner = UUID.randomUUID().toString();
        Boolean result = redis.opsForValue().setIfAbsent(KEY_LOCK + key, owner, ttl);
        return Boolean.TRUE.equals(result) ? owner : null;
    }

    private static final String UNLOCK_LUA =
        "if redis.call('get', KEYS[1]) == ARGV[1] then " +
        "  return redis.call('del', KEYS[1]) " +
        "else " +
        "  return 0 " +
        "end";

    /**
     * 释放分布式锁, 只允许锁的持有者 (owner) 释放。
     * 使用 Lua CAS 脚本防止误删其他持有者的锁。
     */
    public void unlock(String key, String owner) {
        if (owner == null) return;
        redis.execute(new DefaultRedisScript<>(UNLOCK_LUA, Long.class),
                List.of(KEY_LOCK + key), owner);
    }
}
