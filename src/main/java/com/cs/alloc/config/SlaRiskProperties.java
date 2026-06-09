package com.cs.alloc.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.HashMap;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "cs.sla")
public class SlaRiskProperties {
    private boolean enabled = true;
    private long intervalMs = 5000;
    private double vipWeight = 25.0;
    private double waitWeight = 0.5;
    private double availabilityWeight = 20.0;
    private double loadWeight = 15.0;
    private double ahtMultiplier = 0.1;
    private double heartbeatPenalty = 30.0;
    private double riskThreshold = 75.0;
    private long degradationTimeoutSeconds = 600;
    private long snapshotIntervalSeconds = 30;
    private long pinnedTtlSeconds = 3600;
    private long snapshotTtlSeconds = 300;
    private Map<Long, Long> fallbackSkillGroups = new HashMap<>();
}
