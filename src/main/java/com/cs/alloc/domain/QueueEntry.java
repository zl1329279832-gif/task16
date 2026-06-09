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
    private Boolean pinned;
    private Long originalSkillGroupId;
    private LocalDateTime joinedAt;
}
