package com.cs.alloc.service;

import com.cs.alloc.domain.QueueEntry;
import com.cs.alloc.domain.Session;
import com.cs.alloc.mapper.QueueEntryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class QueueService {
    private final QueueEntryMapper queueEntryMapper;
    private final RedisService redisService;

    @Transactional
    public QueueEntry join(Session session, int vipLevel) {
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
        queueEntryMapper.insert(entry);
        redisService.addToQueue(session.getSkillGroupId(), session.getId(), priorityScore);
        log.info("客户入队: sessionId={}, position={}", session.getId(), position);
        return entry;
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

    private int calculatePriority(int vipLevel, LocalDateTime joinedAt) {
        long waitSeconds = java.time.temporal.ChronoUnit.SECONDS.between(joinedAt, LocalDateTime.now());
        return vipLevel * 10 + (int) waitSeconds;
    }
}
