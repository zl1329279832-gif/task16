package com.customerservice.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class QueuePositionInfo {
    private Long sessionId;
    private int position;
    private int estimatedWaitSeconds;
}
