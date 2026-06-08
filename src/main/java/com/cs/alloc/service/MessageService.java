package com.cs.alloc.service;

import com.cs.alloc.common.BizException;
import com.cs.alloc.domain.Message;
import com.cs.alloc.domain.Session;
import com.cs.alloc.mapper.MessageMapper;
import com.cs.alloc.mapper.SessionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Duration;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class MessageService {
    private final MessageMapper messageMapper;
    private final SessionMapper sessionMapper;
    private final RedisService redisService;
    private final MessageQueue messageQueue;
    private static final Duration IDEMPOTENCY_TTL = Duration.ofMinutes(10);

    @Transactional
    public Message sendMessage(long sessionId, String senderId, String senderType, String content, String msgType, String idempotencyKey) {
        Session session = sessionMapper.selectById(sessionId);
        if (session == null) throw new BizException("会话不存在");
        if ("CLOSED".equals(session.getStatus())) throw new BizException("会话已结束");
        if (idempotencyKey != null && !idempotencyKey.isEmpty()) {
            if (!redisService.trySetIdempotencyKey(idempotencyKey, idempotencyKey, IDEMPOTENCY_TTL)) {
                Message existing = messageMapper.selectByIdempotencyKey(idempotencyKey);
                if (existing != null) { log.info("重复消息被拦截: key={}", idempotencyKey); return existing; }
            }
        }
        Message message = new Message();
        message.setSessionId(sessionId); message.setSenderId(senderId); message.setSenderType(senderType);
        message.setContent(content); message.setMsgType(msgType != null ? msgType : "TEXT"); message.setIdempotencyKey(idempotencyKey);
        messageMapper.insert(message);
        messageQueue.publish(MessageQueue.Topics.CHAT_MESSAGE, String.format("{\"id\":%d,\"sessionId\":%d,\"senderId\":\"%s\",\"senderType\":\"%s\",\"content\":\"%s\",\"msgType\":\"%s\"}", message.getId(), sessionId, senderId, senderType, content.replace("\"", "\\\"").replace("\n", "\\n"), message.getMsgType()));
        return message;
    }

    @Transactional
    public Message sendSystemMessage(long sessionId, String content) {
        return sendMessage(sessionId, "SYSTEM", "SYSTEM", content, "SYSTEM", null);
    }

    public List<Message> getHistory(long sessionId, int page, int pageSize) {
        return messageMapper.selectBySessionId(sessionId, (page - 1) * pageSize, pageSize);
    }

    public long getMessageCount(long sessionId) { return messageMapper.countBySessionId(sessionId); }
}
