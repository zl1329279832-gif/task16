package com.cs.alloc.domain;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class AgentState {
    private Long agentId;
    private Integer currentLoad;
    private LocalDateTime lastHeartbeat;
    private LocalDateTime updatedAt;
}
