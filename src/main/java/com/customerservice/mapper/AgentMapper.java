package com.customerservice.mapper;

import com.customerservice.model.entity.Agent;
import com.customerservice.model.enums.AgentStatus;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface AgentMapper {

    Agent selectById(@Param("id") Long id);

    Agent selectByUsername(@Param("username") String username);

    List<Agent> selectByStatus(@Param("status") String status);

    List<Agent> selectAvailableBySkillGroup(@Param("skillGroupId") Long skillGroupId);

    List<Agent> selectAllOnline();

    int updateStatus(@Param("id") Long id, @Param("status") String status);

    int incrementLoad(@Param("id") Long id);

    int decrementLoad(@Param("id") Long id);

    int updateMaxConcurrent(@Param("id") Long id, @Param("maxConcurrent") int maxConcurrent);

    int insert(Agent agent);

    int updateLastOnlineAt(@Param("id") Long id);
}
