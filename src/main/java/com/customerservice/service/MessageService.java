package com.customerservice.service;

import com.customerservice.exception.BusinessException;
import com.customerservice.mapper.CustomerMapper;
import com.customerservice.mapper.MessageMapper;
import com.customerservice.mapper.SessionMapper;
import com.customerservice.model.dto.MessageRequest;
import com.customerservice.model.dto.WsEvent;
import com.customerservice.model.entity.ChatMessage;
import com.customerservice.model.entity.ChatSession;
import com.customerservice.model.entity.Customer;
import com.customerservice.model.enums.*;
import com.customerservice.mq.MessageQueue;
import com.customerservice.mq.MqTopics;
import com.customerservice.websocket.WebSocketSessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class MessageService {
    private static final Logger log = LoggerFactory.getLogger(MessageService.class);
    private static final String MSG_DEDUP_KEY = "cs:msg:dedup:";

    private final MessageMapper messageMapper;
    private final SessionMapper sessionMapper;
    private final CustomerMapper customerMapper;
    private final StringRedisTemplate redisTemplate;
    private final WebSocketSessionManager wsSessionManager;
    private final MessageQueue messageQueue;

    public MessageService(MessageMapper messageMapper,
                          SessionMapper sessionMapper,
                          CustomerMapper customerMapper,
                          StringRedisTemplate redisTemplate,
                          WebSocketSessionManager wsSessionManager,
                          MessageQueue messageQueue) {
        this.messageMapper = messageMapper;
        this.sessionMapper = sessionMapper;
        this.customerMapper = customerMapper;
        this.redisTemplate = redisTemplate;
        this.wsSessionManager = wsSessionManager;
        this.messageQueue = messageQueue;
    }

    /**
     * Send a message with deduplication support.
     * Client provides messageUid; if duplicate, returns the existing message.
     */
    public ChatMessage sendMessage(MessageRequest req) {
        // Generate messageUid if not provided
        String messageUid = req.getMessageUid();
        if (messageUid == null || messageUid.isEmpty()) {
            messageUid = UUID.randomUUID().toString();
        }

        // Deduplication check via Redis
        String dedupKey = MSG_DEDUP_KEY + messageUid;
        Boolean isNew = redisTemplate.opsForValue().setIfAbsent(dedupKey, "1", 5, TimeUnit.MINUTES);
        if (Boolean.FALSE.equals(isNew)) {
            // Duplicate message - return existing
            ChatMessage existing = messageMapper.selectByMessageUid(messageUid);
            if (existing != null) {
                log.info("Duplicate message detected: {}", messageUid);
                return existing;
            }
        }

        ChatSession session = sessionMapper.selectById(req.getSessionId());
        if (session == null) {
            throw new BusinessException("Session not found: " + req.getSessionId());
        }
        if (session.getStatus() == SessionStatus.CLOSED) {
            throw new BusinessException("Cannot send message to closed session");
        }

        // Get next sequence number (atomic via Redis)
        String seqKey = "cs:session:seq:" + req.getSessionId();
        Long seqNo = redisTemplate.opsForValue().increment(seqKey);

        ChatMessage message = new ChatMessage();
        message.setMessageUid(messageUid);
        message.setSessionId(req.getSessionId());
        message.setSenderType(SenderType.valueOf(req.getSenderType()));
        message.setSenderId(req.getSenderId());
        message.setContentType(ContentType.valueOf(req.getContentType()));
        message.setContent(req.getContent());
        message.setStatus(MessageStatus.SENT);
        message.setSequenceNo(seqNo);

        messageMapper.insert(message);
        sessionMapper.updateLastActiveAt(req.getSessionId());

        // Push to both parties via WebSocket
        deliverMessage(session, message);

        // Publish to MQ
        Map<String, Object> mqData = new HashMap<>();
        mqData.put("sessionId", session.getId());
        mqData.put("messageId", message.getId());
        mqData.put("senderType", message.getSenderType().name());
        messageQueue.publish(MqTopics.MESSAGE_SENT, mqData);

        return message;
    }

    /**
     * Send a system message (used for transfer notifications, etc.)
     */
    public ChatMessage sendSystemMessage(Long sessionId, String content) {
        MessageRequest req = new MessageRequest();
        req.setSessionId(sessionId);
        req.setContent(content);
        req.setContentType("SYSTEM");
        req.setSenderType("SYSTEM");
        req.setMessageUid(UUID.randomUUID().toString());
        return sendMessage(req);
    }

    public List<ChatMessage> getSessionMessages(Long sessionId) {
        return messageMapper.selectBySessionId(sessionId);
    }

    public List<ChatMessage> getMessagesAfterSeq(Long sessionId, Long afterSeq) {
        return messageMapper.selectBySessionIdAfterSeq(sessionId, afterSeq);
    }

    /**
     * Deliver a message to both customer and agent via WebSocket.
     */
    private void deliverMessage(ChatSession session, ChatMessage message) {
        Map<String, Object> msgData = new HashMap<>();
        msgData.put("messageId", message.getId());
        msgData.put("messageUid", message.getMessageUid());
        msgData.put("sessionId", message.getSessionId());
        msgData.put("senderType", message.getSenderType().name());
        msgData.put("senderId", message.getSenderId());
        msgData.put("contentType", message.getContentType().name());
        msgData.put("content", message.getContent());
        msgData.put("sequenceNo", message.getSequenceNo());
        msgData.put("createdAt", message.getCreatedAt());

        WsEvent event = WsEvent.of("MESSAGE", session.getId(), msgData);

        // Send to customer
        Customer customer = customerMapper.selectById(session.getCustomerId());
        if (customer != null) {
            wsSessionManager.sendToCustomer(customer.getCustomerUid(), event);
        }

        // Send to agent
        if (session.getAgentId() != null) {
            wsSessionManager.sendToAgent(session.getAgentId(), event);
        }
    }
}
