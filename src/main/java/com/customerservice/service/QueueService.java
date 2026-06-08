package com.customerservice.service;

import com.customerservice.mapper.QueueEntryMapper;
import com.customerservice.model.dto.QueuePositionInfo;
import com.customerservice.model.dto.WsEvent;
import com.customerservice.model.entity.ChatSession;
import com.customerservice.model.entity.QueueEntry;
import com.customerservice.model.enums.VipLevel;
import com.customerservice.websocket.WebSocketSessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class QueueService {
    private static final Logger log = LoggerFactory.getLogger(QueueService.class);
    private static final int AVG_SERVICE_SECONDS = 120; // estimated avg service time per session

    private final QueueEntryMapper queueEntryMapper;
    private final StringRedisTemplate redisTemplate;
    private final WebSocketSessionManager wsSessionManager;

    public QueueService(QueueEntryMapper queueEntryMapper,
                        StringRedisTemplate redisTemplate,
                        WebSocketSessionManager wsSessionManager) {
        this.queueEntryMapper = queueEntryMapper;
        this.redisTemplate = redisTemplate;
        this.wsSessionManager = wsSessionManager;
    }

    /**
     * Enqueue a session. Priority score = VIP weight + wait-time bonus.
     */
    public QueueEntry enqueue(ChatSession session) {
        QueueEntry entry = new QueueEntry();
        entry.setSessionId(session.getId());
        entry.setCustomerId(session.getCustomerId());
        entry.setSkillGroupId(session.getSkillGroupId());
        entry.setVipLevel(session.getVipLevel());
        entry.setPriorityScore(calculatePriority(session.getVipLevel(), 0));
        entry.setEnqueueAt(LocalDateTime.now());

        queueEntryMapper.insert(entry);
        log.info("Session [{}] enqueued, priority={}", session.getId(), entry.getPriorityScore());
        return entry;
    }

    /**
     * Remove a session from the queue (assigned or cancelled).
     */
    public void dequeue(Long sessionId) {
        queueEntryMapper.deleteBySessionId(sessionId);
        log.info("Session [{}] dequeued", sessionId);
    }

    /**
     * Get ordered queue entries for a skill group (or all if skillGroupId is null).
     */
    public List<QueueEntry> getOrderedQueue(Long skillGroupId) {
        if (skillGroupId != null) {
            return queueEntryMapper.selectBySkillGroupOrderByPriority(skillGroupId);
        }
        return queueEntryMapper.selectAllOrderByPriority();
    }

    /**
     * Get queue position for a session.
     */
    public int getPosition(Long sessionId) {
        QueueEntry entry = queueEntryMapper.selectBySessionId(sessionId);
        if (entry == null) return -1;
        return queueEntryMapper.selectPosition(sessionId);
    }

    /**
     * Broadcast queue position updates to all waiting customers.
     */
    public void broadcastQueuePositions(String customerUidResolver) {
        List<QueueEntry> entries = queueEntryMapper.selectAllOrderByPriority();
        for (int i = 0; i < entries.size(); i++) {
            QueueEntry entry = entries.get(i);
            int position = i + 1;
            int estimatedWait = position * AVG_SERVICE_SECONDS;

            QueuePositionInfo info = new QueuePositionInfo(
                    entry.getSessionId(), position, estimatedWait);

            // Use Redis to look up customerUid from customerId
            String customerUid = redisTemplate.opsForValue()
                    .get("cs:customer:uid:" + entry.getCustomerId());
            if (customerUid != null) {
                wsSessionManager.sendToCustomer(customerUid,
                        WsEvent.of("QUEUE_POSITION", entry.getSessionId(), info));
            }
        }
    }

    public int getQueueSize() {
        return queueEntryMapper.countAll();
    }

    /**
     * Calculate priority score.
     * Higher = served sooner. VIP weight + time-based aging bonus.
     */
    public int calculatePriority(VipLevel vipLevel, long waitSeconds) {
        int vipWeight = vipLevel != null ? vipLevel.getPriorityWeight() : 0;
        // Add 1 point for every 30 seconds of waiting (aging factor)
        int waitBonus = (int) (waitSeconds / 30);
        return vipWeight + waitBonus;
    }

    /**
     * Recalculate and update priority scores for all queue entries (aging).
     */
    public void refreshPriorities() {
        List<QueueEntry> entries = queueEntryMapper.selectAllOrderByPriority();
        for (QueueEntry entry : entries) {
            long waitSeconds = Duration.between(entry.getEnqueueAt(), LocalDateTime.now()).getSeconds();
            int newPriority = calculatePriority(entry.getVipLevel(), waitSeconds);
            if (newPriority != entry.getPriorityScore()) {
                // Inline update via delete + re-insert to maintain index ordering
                queueEntryMapper.deleteBySessionId(entry.getSessionId());
                entry.setPriorityScore(newPriority);
                queueEntryMapper.insert(entry);
            }
        }
    }
}
