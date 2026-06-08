package com.cs.alloc.service;

import com.cs.alloc.common.BizException;
import com.cs.alloc.domain.Message;
import com.cs.alloc.domain.Session;
import com.cs.alloc.mapper.MessageMapper;
import com.cs.alloc.mapper.SessionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MessageServiceTest {
    @Mock private MessageMapper messageMapper; @Mock private SessionMapper sessionMapper;
    @Mock private RedisService redisService; @Mock private MessageQueue messageQueue;
    private MessageService svc;

    @BeforeEach void setUp() { svc = new MessageService(messageMapper, sessionMapper, redisService, messageQueue); }

    @Test @DisplayName("正常发送")
    void sendOk() {
        when(sessionMapper.selectById(1L)).thenReturn(activeSession());
        when(redisService.trySetIdempotencyKey(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        Message m = svc.sendMessage(1L, "100", "CUSTOMER", "hi", "TEXT", "k1");
        assertThat(m.getContent()).isEqualTo("hi");
        verify(messageMapper).insert(any());
    }

    @Test @DisplayName("重复消息幂等")
    void duplicate() {
        when(sessionMapper.selectById(1L)).thenReturn(activeSession());
        when(redisService.trySetIdempotencyKey(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        Message ex = new Message(); ex.setId(999L); ex.setContent("hi");
        when(messageMapper.selectByIdempotencyKey("dup")).thenReturn(ex);
        assertThat(svc.sendMessage(1L, "100", "CUSTOMER", "hi", "TEXT", "dup").getId()).isEqualTo(999L);
        verify(messageMapper, never()).insert(any());
    }

    @Test @DisplayName("已关闭会话拒绝")
    void closedReject() {
        Session s = new Session(); s.setId(1L); s.setStatus("CLOSED");
        when(sessionMapper.selectById(1L)).thenReturn(s);
        assertThatThrownBy(() -> svc.sendMessage(1L, "1", "CUSTOMER", "hi", "TEXT", null)).isInstanceOf(BizException.class);
    }

    @Test @DisplayName("不存在会话拒绝")
    void notFound() {
        when(sessionMapper.selectById(999L)).thenReturn(null);
        assertThatThrownBy(() -> svc.sendMessage(999L, "1", "CUSTOMER", "hi", "TEXT", null)).isInstanceOf(BizException.class);
    }

    private Session activeSession() { Session s = new Session(); s.setId(1L); s.setSessionNo("CS1"); s.setStatus("ACTIVE"); s.setCustomerId(100L); s.setAgentId(10L); return s; }
}
