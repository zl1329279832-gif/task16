package com.customerservice.model.entity;

import com.customerservice.model.enums.VipLevel;
import lombok.Data;
import java.time.LocalDateTime;

@Data
public class QueueEntry {
    private Long id;
    private Long sessionId;
    private Long customerId;
    private Long skillGroupId;
    private VipLevel vipLevel;
    private Integer priorityScore;
    private LocalDateTime enqueueAt;
}
