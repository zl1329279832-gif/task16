package com.cs.alloc.domain;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class SlaRiskHistory {
    private Long id;
    private Long sessionId;
    private Long skillGroupId;
    private Double riskScore;
    private Integer vipLevel;
    private Long waitSeconds;
    private Integer availableAgents;
    private Double avgAgentLoad;
    private Long calculatedAt;
}
