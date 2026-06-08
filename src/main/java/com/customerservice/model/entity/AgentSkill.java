package com.customerservice.model.entity;

import lombok.Data;

@Data
public class AgentSkill {
    private Long id;
    private Long agentId;
    private Long skillGroupId;
    private Integer proficiency;
}
