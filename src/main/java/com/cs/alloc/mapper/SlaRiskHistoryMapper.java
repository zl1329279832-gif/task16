package com.cs.alloc.mapper;

import com.cs.alloc.domain.SlaRiskHistory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SlaRiskHistoryMapper {
    void insert(SlaRiskHistory history);
    void deleteOlderThan(@Param("olderThanSeconds") long olderThanSeconds);
}
