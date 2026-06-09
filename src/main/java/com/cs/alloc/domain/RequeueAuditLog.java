package com.cs.alloc.domain;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class RequeueAuditLog {
    private Long id;
    private Long sessionId;
    private String action;
    private String operatorId;
    private String operatorType;
    private Long skillGroupId;
    private Integer oldPosition;
    private Integer newPosition;
    private Integer oldPriority;
    private Integer newPriority;
    private String oldRiskLevel;
    private String newRiskLevel;
    private String detail;
    private LocalDateTime createdAt;
}
