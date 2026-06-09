package com.cs.alloc.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueueSnapshot {
    private String snapshotId;
    private Long skillGroupId;
    private List<QueueEntry> entries;
    private Long capturedAt;
    private Integer totalEntries;
    private Double avgRiskScore;
}
