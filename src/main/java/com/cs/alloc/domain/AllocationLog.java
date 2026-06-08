package com.cs.alloc.domain;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class AllocationLog {
    private Long id;
    private Long sessionId;
    private Long agentId;
    private String action;
    private String reason;
    private String scoreDetail;
    private LocalDateTime createdAt;
}
