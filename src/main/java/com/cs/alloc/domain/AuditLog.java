package com.cs.alloc.domain;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class AuditLog {
    private Long id;
    private String operatorId;
    private String operatorType;
    private String action;
    private String targetType;
    private String targetId;
    private String detail;
    private String ipAddress;
    private LocalDateTime createdAt;
}
