package com.cs.alloc.domain;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class Customer {
    private Long id;
    private String name;
    private Integer vipLevel;
    private String source;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
