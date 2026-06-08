package com.customerservice.mapper;

import com.customerservice.model.entity.ChatSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface SessionMapper {

    ChatSession selectById(@Param("id") Long id);

    ChatSession selectBySessionNo(@Param("sessionNo") String sessionNo);

    List<ChatSession> selectByAgentId(@Param("agentId") Long agentId);

    List<ChatSession> selectActiveByAgentId(@Param("agentId") Long agentId);

    List<ChatSession> selectByCustomerId(@Param("customerId") Long customerId);

    List<ChatSession> selectByStatus(@Param("status") String status);

    List<ChatSession> selectIdleSessions(@Param("timeoutSeconds") int timeoutSeconds);

    int insert(ChatSession session);

    int updateStatus(@Param("id") Long id, @Param("status") String status);

    int updateAgentAndStatus(@Param("id") Long id, @Param("agentId") Long agentId,
                             @Param("status") String status);

    int updateLastActiveAt(@Param("id") Long id);

    int closeSession(@Param("id") Long id, @Param("closeReason") String closeReason);
}
