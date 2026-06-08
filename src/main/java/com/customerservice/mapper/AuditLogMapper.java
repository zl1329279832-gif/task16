package com.customerservice.mapper;

import com.customerservice.model.entity.AuditLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface AuditLogMapper {

    int insert(AuditLog log);

    List<AuditLog> selectByTarget(@Param("targetType") String targetType,
                                   @Param("targetId") Long targetId);

    List<AuditLog> selectByOperator(@Param("operatorType") String operatorType,
                                     @Param("operatorId") Long operatorId);
}
