package com.cs.alloc.domain;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class Agent {
    private Long id;
    private String name;
    private Long skillGroupId;
    private Integer maxCapacity;
    private Boolean isSupervisor;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
