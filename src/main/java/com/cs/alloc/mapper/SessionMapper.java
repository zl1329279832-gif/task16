package com.cs.alloc.mapper;

import com.cs.alloc.domain.Session;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface SessionMapper {
    Session selectById(@Param("id") Long id);
    Session selectBySessionNo(@Param("sessionNo") String sessionNo);
    List<Session> selectByAgentIdAndStatus(@Param("agentId") Long agentId, @Param("status") String status);
    List<Session> selectByCustomerIdAndStatus(@Param("customerId") Long customerId, @Param("status") String status);
    void insert(Session session);
    void updateStatus(@Param("id") Long id, @Param("status") String status);
    void assignAgent(@Param("id") Long id, @Param("agentId") Long agentId, @Param("status") String status);
    void close(@Param("id") Long id);
    int countActiveByAgentId(@Param("agentId") Long agentId);
}
