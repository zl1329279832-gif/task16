package com.cs.alloc.mapper;

import com.cs.alloc.domain.AllocationLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface AllocationLogMapper {
    void insert(AllocationLog log);
    List<AllocationLog> selectBySessionId(@Param("sessionId") Long sessionId);
}
