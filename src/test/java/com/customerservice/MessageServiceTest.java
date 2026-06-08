package com.customerservice;

import com.customerservice.mapper.CustomerMapper;
import com.customerservice.mapper.MessageMapper;
import com.customerservice.mapper.SessionMapper;
import com.customerservice.model.dto.MessageRequest;
import com.customerservice.model.entity.ChatMessage;
import com.customerservice.model.entity.ChatSession;
import com.customerservice.model.enums.MessageStatus;
import com.customerservice.model.enums.SessionStatus;
import com.customerservice.mq.MessageQueue;
import com.customerservice.service.MessageService;
import com.customerservice.websocket.WebSocketSessionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MessageServiceTest {

    @Mock private MessageMapper messageMapper;
    @Mock private SessionMapper sessionMapper;
    @Mock private CustomerMapper customerMapper;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private WebSocketSessionManager wsSessionManager;
    @Mock private MessageQueue messageQueue;

    @InjectMocks
    private MessageService messageService;

    private ChatSession activeSession;

    @BeforeEach
    void setUp() {
        activeSession = new ChatSession();
        activeSession.setId(1L);
        activeSession.setCustomerId(10L);
        activeSession.setAgentId(20L);
        activeSession.setStatus(SessionStatus.ACTIVE);
        activeSession.setSessionNo("CS-TEST-001");
    }

    @Test
    void sendMessage_shouldSucceed() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(true);
        when(valueOps.increment(anyString())).thenReturn(1L);
        when(sessionMapper.selectById(1L)).thenReturn(activeSession);

        MessageRequest req = new MessageRequest();
        req.setSessionId(1L);
        req.setContent("Hello");
        req.setContentType("TEXT");
        req.setSenderType("CUSTOMER");
        req.setMessageUid("uid-001");

        ChatMessage result = messageService.sendMessage(req);

        assertNotNull(result);
        assertEquals("Hello", result.getContent());
        assertEquals(MessageStatus.SENT, result.getStatus());
        assertEquals(1L, result.getSequenceNo());
        verify(messageMapper).insert(any(ChatMessage.class));
        verify(sessionMapper).updateLastActiveAt(1L);
    }

    @Test
    void sendMessage_duplicateShouldReturnExisting() {
        ChatMessage existing = new ChatMessage();
        existing.setId(99L);
        existing.setMessageUid("uid-dup");
        existing.setContent("Existing");

        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(false); // duplicate
        when(messageMapper.selectByMessageUid("uid-dup")).thenReturn(existing);

        MessageRequest req = new MessageRequest();
        req.setSessionId(1L);
        req.setContent("Hello again");
        req.setContentType("TEXT");
        req.setSenderType("CUSTOMER");
        req.setMessageUid("uid-dup");

        ChatMessage result = messageService.sendMessage(req);

        assertEquals(99L, result.getId());
        assertEquals("Existing", result.getContent());
        verify(messageMapper, never()).insert(any());
    }

    @Test
    void sendMessage_toClosedSessionShouldFail() {
        activeSession.setStatus(SessionStatus.CLOSED);

        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(true);
        when(sessionMapper.selectById(1L)).thenReturn(activeSession);

        MessageRequest req = new MessageRequest();
        req.setSessionId(1L);
        req.setContent("test");
        req.setContentType("TEXT");
        req.setSenderType("CUSTOMER");
        req.setMessageUid("uid-closed");

        assertThrows(Exception.class, () -> messageService.sendMessage(req));
    }
}
