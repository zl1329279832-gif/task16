package com.customerservice.model.dto;

import com.customerservice.model.enums.AgentStatus;
import lombok.Data;
import javax.validation.constraints.NotNull;

@Data
public class AgentStatusRequest {
    @NotNull(message = "Agent ID is required")
    private Long agentId;
    @NotNull(message = "Status is required")
    private AgentStatus status;
}
