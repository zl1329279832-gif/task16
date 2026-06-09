package com.cs.alloc.service;

import com.cs.alloc.domain.QueueEntry;
import com.cs.alloc.domain.Session;
import com.cs.alloc.mapper.CustomerMapper;
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
    private final CustomerMapper customerMapper;

    /**
     * 入队: 三层防护 — Redis锁 → DB查重 → INSERT IGNORE。
     * 重复入队不改变排队序号。
     * 自动设置 SLA 截止时间, VIP 客户自动提升优先级。
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

            // 计算 SLA 截止时间
            int slaTimeout = calculateSlaTimeout(vipLevel);
            LocalDateTime slaDeadline = LocalDateTime.now().plusSeconds(slaTimeout);

            QueueEntry entry = new QueueEntry();
            entry.setSessionId(session.getId());
            entry.setCustomerId(session.getCustomerId());
            entry.setSkillGroupId(session.getSkillGroupId());
            entry.setPriorityScore(priorityScore);
            entry.setPosition(position);
            entry.setJoinedAt(LocalDateTime.now());
            entry.setSlaDeadline(slaDeadline);
            entry.setRiskScore(0);
            entry.setRiskLevel("LOW");
            entry.setPinned(false);

            // VIP 客户入队时自动提升优先级
            if (vipLevel > 0) {
                int vipBonus = vipLevel * 1000;
                entry.setPriorityScore(priorityScore + vipBonus);
            }

            // INSERT IGNORE: DB 层兜底, 防止并发穿透
            int rows = queueEntryMapper.insertIgnore(entry);
            if (rows == 0) {
                // 唯一键冲突, 返回已有记录
                return queueEntryMapper.selectBySessionId(session.getId());
            }

            redisService.addToQueue(session.getSkillGroupId(), session.getId(), entry.getPriorityScore());
            log.info("客户入队: sessionId={}, position={}, vipLevel={}, slaDeadline={}",
                    session.getId(), position, vipLevel, slaDeadline);
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
        redisService.removeSlaRisk(sessionId);
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

    /**
     * 根据 VIP 等级计算 SLA 超时时间。
     * VIP 等级越高, 超时越短。
     */
    private int calculateSlaTimeout(int vipLevel) {
        int baseTimeout = 1800; // 默认 30 分钟
        if (vipLevel <= 0) return baseTimeout;
        double multiplier = Math.max(1.0 - vipLevel * 0.25, 0.25);
        return (int) (baseTimeout * multiplier);
    }
}
