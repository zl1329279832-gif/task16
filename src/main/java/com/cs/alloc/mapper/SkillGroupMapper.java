package com.cs.alloc.mapper;

import com.cs.alloc.domain.SkillGroup;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface SkillGroupMapper {
    SkillGroup selectById(@Param("id") Long id);
    List<SkillGroup> selectAllActive();
    void updateAvgHandlingTime(@Param("id") Long id, @Param("avgHandlingTimeSeconds") Long avgHandlingTimeSeconds);
}
