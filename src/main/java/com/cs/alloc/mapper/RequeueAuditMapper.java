package com.cs.alloc.mapper;

import com.cs.alloc.domain.RequeueAuditLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface RequeueAuditMapper {
    void insert(RequeueAuditLog log);
    List<RequeueAuditLog> selectBySessionId(@Param("sessionId") Long sessionId);
    List<RequeueAuditLog> selectByAction(@Param("action") String action,
                                         @Param("offset") int offset,
                                         @Param("limit") int limit);
    List<RequeueAuditLog> selectRecent(@Param("offset") int offset, @Param("limit") int limit);
}
