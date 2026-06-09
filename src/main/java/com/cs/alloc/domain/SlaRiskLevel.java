package com.cs.alloc.domain;

public enum SlaRiskLevel {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL;

    public static SlaRiskLevel fromScore(int score, int mediumThreshold, int highThreshold, int criticalThreshold) {
        if (score >= criticalThreshold) return CRITICAL;
        if (score >= highThreshold) return HIGH;
        if (score >= mediumThreshold) return MEDIUM;
        return LOW;
    }
}
