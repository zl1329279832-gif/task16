package com.customerservice.model.entity;

import com.customerservice.model.enums.AgentStatus;
import lombok.Data;
import java.time.LocalDateTime;

@Data
public class Agent {
    private Long id;
    private String username;
    private String displayName;
    private AgentStatus status;
    private Integer maxConcurrent;
    private Integer currentLoad;
    private Boolean isSupervisor;
    private LocalDateTime lastOnlineAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public boolean hasCapacity() {
        return status == AgentStatus.ONLINE && currentLoad < maxConcurrent;
    }

    public int remainingCapacity() {
        return Math.max(0, maxConcurrent - currentLoad);
    }
}
