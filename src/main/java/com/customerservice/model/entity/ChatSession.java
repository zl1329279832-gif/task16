package com.customerservice.model.entity;

import com.customerservice.model.enums.SessionStatus;
import com.customerservice.model.enums.VipLevel;
import com.customerservice.model.enums.CloseReason;
import lombok.Data;
import java.time.LocalDateTime;

@Data
public class ChatSession {
    private Long id;
    private String sessionNo;
    private Long customerId;
    private Long agentId;
    private Long skillGroupId;
    private SessionStatus status;
    private VipLevel vipLevel;
    private LocalDateTime queueStartAt;
    private LocalDateTime assignAt;
    private LocalDateTime closeAt;
    private CloseReason closeReason;
    private LocalDateTime lastActiveAt;
    private String metadata;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
