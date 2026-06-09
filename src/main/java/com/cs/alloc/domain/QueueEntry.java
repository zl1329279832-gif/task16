package com.cs.alloc.domain;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class QueueEntry {
    private Long id;
    private Long sessionId;
    private Long customerId;
    private Long skillGroupId;
    private Integer priorityScore;
    private Integer position;
    private LocalDateTime joinedAt;
    private LocalDateTime slaDeadline;
    private Integer riskScore;
    private String riskLevel;
    private Boolean pinned;
    private String pinnedBy;
    private Long originalSkillGroupId;
}
