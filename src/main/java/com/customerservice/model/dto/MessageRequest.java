package com.customerservice.model.dto;

import lombok.Data;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

@Data
public class MessageRequest {
    @NotNull(message = "Session ID is required")
    private Long sessionId;
    @NotBlank(message = "Content is required")
    private String content;
    private String contentType = "TEXT";
    private String messageUid;
    private String senderType;
    private Long senderId;
}
