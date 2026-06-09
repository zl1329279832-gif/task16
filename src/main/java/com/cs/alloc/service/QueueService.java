package com.cs.alloc.service;

import com.cs.alloc.domain.QueueEntry;
import com.cs.alloc.domain.Session;
import com.cs.alloc.mapper.QueueEntryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class QueueService {
    private final QueueEntryMapper queueEntryMapper;
    private final RedisService redisService;

    /**
     * 入队: 三层防护 — Redis锁 → DB查重 → INSERT IGNORE。
     * 重复入队不改变排队序号。
     */
    @Transactional
    public QueueEntry join(Session session, int vipLevel) {
        String lockKey = "queue:session:" + session.getId();
        String lockOwner = redisService.tryLock(lockKey, Duration.ofSeconds(5));
        try {
            // 锁内查重: 已在队列中则直接返回, 不改变位置或分数
            QueueEntry existing = queueEntryMapper.selectBySessionId(session.getId());
            if (existing != null) return existing;

            int priorityScore = calculatePriority(vipLevel, LocalDateTime.now());
            int position = queueEntryMapper.countBySkillGroupId(session.getSkillGroupId()) + 1;
            QueueEntry entry = new QueueEntry();
            entry.setSessionId(session.getId());
            entry.setCustomerId(session.getCustomerId());
            entry.setSkillGroupId(session.getSkillGroupId());
            entry.setPriorityScore(priorityScore);
            entry.setPosition(position);
            entry.setJoinedAt(LocalDateTime.now());

            // INSERT IGNORE: DB 层兜底, 防止并发穿透
            int rows = queueEntryMapper.insertIgnore(entry);
            if (rows == 0) {
                // 唯一键冲突, 返回已有记录
                return queueEntryMapper.selectBySessionId(session.getId());
            }

            redisService.addToQueue(session.getSkillGroupId(), session.getId(), priorityScore);
            log.info("客户入队: sessionId={}, position={}", session.getId(), position);
            return entry;
        } finally {
            if (lockOwner != null) {
                redisService.unlock(lockKey, lockOwner);
            }
        }
    }

    @Transactional
    public void leave(long sessionId) {
        QueueEntry entry = queueEntryMapper.selectBySessionId(sessionId);
        if (entry == null) return;
        queueEntryMapper.deleteBySessionId(sessionId);
        redisService.removeFromQueue(entry.getSkillGroupId(), sessionId);
    }

    @Transactional
    public void rejoin(Session session) {
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
    }

    public int getPosition(long sessionId) {
        QueueEntry entry = queueEntryMapper.selectBySessionId(sessionId);
        if (entry == null) return -1;
        long pos = redisService.getQueuePosition(entry.getSkillGroupId(), sessionId);
        return pos >= 0 ? (int) pos + 1 : -1;
    }

    public int getWaitCount(long skillGroupId) {
        return (int) redisService.getQueueSize(skillGroupId);
    }

    public List<QueueEntry> getAllWaiting() {
        return queueEntryMapper.selectAll();
    }

    public List<QueueEntry> getWaitingByGroup(long skillGroupId) {
        return queueEntryMapper.selectBySkillGroupId(skillGroupId);
    }

    public void updatePriorityScore(long sessionId, int newScore) {
        QueueEntry entry = queueEntryMapper.selectBySessionId(sessionId);
        if (entry == null) return;
        queueEntryMapper.updatePriorityScore(sessionId, newScore);
        redisService.updateQueueEntryScore(entry.getSkillGroupId(), sessionId, newScore);
    }

    @Transactional
    public void migrateToSkillGroup(long skillGroupId, long newSkillGroupId) {
        List<QueueEntry> entries = queueEntryMapper.selectBySkillGroupId(skillGroupId);
        int migrated = queueEntryMapper.batchUpdateSkillGroup(skillGroupId, newSkillGroupId);
        for (QueueEntry entry : entries) {
            redisService.removeFromQueue(skillGroupId, entry.getSessionId());
            redisService.addToQueue(newSkillGroupId, entry.getSessionId(),
                    entry.getPriorityScore() != null ? entry.getPriorityScore() : 0);
        }
    }

    public List<QueueEntry> getEntriesByOriginalGroup(long originalSkillGroupId) {
        return queueEntryMapper.selectByOriginalSkillGroupId(originalSkillGroupId);
    }

    public void setPinned(long sessionId, boolean pinned) {
        queueEntryMapper.updatePinned(sessionId, pinned);
    }

    private int calculatePriority(int vipLevel, LocalDateTime joinedAt) {
        long waitSeconds = java.time.temporal.ChronoUnit.SECONDS.between(joinedAt, LocalDateTime.now());
        return vipLevel * 10 + (int) waitSeconds;
    }
}
