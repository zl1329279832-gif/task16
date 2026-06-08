package com.cs.alloc.mapper;

import com.cs.alloc.domain.Message;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface MessageMapper {
    void insert(Message message);
    Message selectByIdempotencyKey(@Param("idempotencyKey") String idempotencyKey);
    List<Message> selectBySessionId(@Param("sessionId") Long sessionId, @Param("offset") int offset, @Param("limit") int limit);
    long countBySessionId(@Param("sessionId") Long sessionId);
}
