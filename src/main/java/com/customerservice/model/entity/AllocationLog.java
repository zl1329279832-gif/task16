package com.customerservice.model.entity;

import com.customerservice.model.enums.AllocationAction;
import lombok.Data;
import java.time.LocalDateTime;

@Data
public class AllocationLog {
    private Long id;
    private Long sessionId;
    private Long fromAgentId;
    private Long toAgentId;
    private AllocationAction action;
    private String reason;
    private LocalDateTime createdAt;
}
