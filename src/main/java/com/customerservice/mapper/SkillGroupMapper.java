package com.customerservice.mapper;

import com.customerservice.model.entity.SkillGroup;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface SkillGroupMapper {

    SkillGroup selectById(@Param("id") Long id);

    SkillGroup selectByName(@Param("name") String name);

    List<SkillGroup> selectAll();

    int insert(SkillGroup skillGroup);
}
