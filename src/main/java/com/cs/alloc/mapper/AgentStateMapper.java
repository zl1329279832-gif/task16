package com.cs.alloc.mapper;

import com.cs.alloc.domain.AgentState;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AgentStateMapper {
    AgentState selectByAgentId(@Param("agentId") Long agentId);
    void upsert(AgentState state);
    void updateLoad(@Param("agentId") Long agentId, @Param("currentLoad") int currentLoad);
}
