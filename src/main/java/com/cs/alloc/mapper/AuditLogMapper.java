package com.cs.alloc.mapper;

import com.cs.alloc.domain.AuditLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface AuditLogMapper {
    void insert(AuditLog log);
    List<AuditLog> selectByTarget(@Param("targetType") String targetType, @Param("targetId") String targetId, @Param("offset") int offset, @Param("limit") int limit);
}
