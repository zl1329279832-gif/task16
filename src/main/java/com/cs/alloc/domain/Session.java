package com.cs.alloc.domain;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class Session {
    private Long id;
    private String sessionNo;
    private Long customerId;
    private Long agentId;
    private Long skillGroupId;
    private String status;
    private Long transferFrom;
    private Integer priorityScore;
    private LocalDateTime assignedAt;
    private LocalDateTime closedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
