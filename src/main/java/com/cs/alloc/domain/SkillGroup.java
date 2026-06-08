package com.cs.alloc.domain;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class SkillGroup {
    private Long id;
    private String name;
    private String description;
    private Integer priority;
    private LocalDateTime createdAt;
}
