package com.cs.alloc.domain;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class Message {
    private Long id;
    private Long sessionId;
    private String senderId;
    private String senderType;
    private String content;
    private String msgType;
    private String idempotencyKey;
    private LocalDateTime createdAt;
}
