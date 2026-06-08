package com.customerservice.model.entity;

import com.customerservice.model.enums.ContentType;
import com.customerservice.model.enums.MessageStatus;
import com.customerservice.model.enums.SenderType;
import lombok.Data;
import java.time.LocalDateTime;

@Data
public class ChatMessage {
    private Long id;
    private String messageUid;
    private Long sessionId;
    private SenderType senderType;
    private Long senderId;
    private ContentType contentType;
    private String content;
    private MessageStatus status;
    private Long sequenceNo;
    private LocalDateTime createdAt;
}
