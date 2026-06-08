package com.customerservice.model.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class SkillGroup {
    private Long id;
    private String name;
    private String description;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
