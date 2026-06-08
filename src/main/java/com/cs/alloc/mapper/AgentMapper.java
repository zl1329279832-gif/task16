package com.cs.alloc.mapper;

import com.cs.alloc.domain.Agent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface AgentMapper {
    Agent selectById(@Param("id") Long id);
    List<Agent> selectBySkillGroupId(@Param("skillGroupId") Long skillGroupId);
    List<Agent> selectAllOnline();
    void insert(Agent agent);
    void updateStatus(@Param("id") Long id, @Param("status") String status);
}
