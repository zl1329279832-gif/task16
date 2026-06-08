package com.customerservice.model.dto;

import lombok.Data;
import javax.validation.constraints.NotNull;

@Data
public class TransferRequest {
    @NotNull(message = "Session ID is required")
    private Long sessionId;
    private Long targetAgentId;
    private Long targetSkillGroupId;
    private String reason;
}
