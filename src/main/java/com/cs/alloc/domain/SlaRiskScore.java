package com.cs.alloc.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SlaRiskScore {
    private Long sessionId;
    private Long customerId;
    private Long skillGroupId;
    private Integer vipLevel;
    private Long waitSeconds;
    private Double riskScore;
    private Integer availableAgents;
    private Double avgAgentLoad;
    private Long historicalAht;
    private Boolean agentHeartbeatOk;
    private Long calculatedAt;
    private Boolean pinned;
    private Boolean vipJumpApplied;
    private Boolean degraded;
}
