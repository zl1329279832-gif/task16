package com.customerservice.mapper;

import com.customerservice.model.entity.AllocationLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface AllocationLogMapper {

    int insert(AllocationLog log);

    List<AllocationLog> selectBySessionId(@Param("sessionId") Long sessionId);
}
