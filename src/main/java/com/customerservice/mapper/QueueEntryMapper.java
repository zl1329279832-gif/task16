package com.customerservice.mapper;

import com.customerservice.model.entity.QueueEntry;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface QueueEntryMapper {

    QueueEntry selectBySessionId(@Param("sessionId") Long sessionId);

    List<QueueEntry> selectBySkillGroupOrderByPriority(@Param("skillGroupId") Long skillGroupId);

    List<QueueEntry> selectAllOrderByPriority();

    int countBySkillGroup(@Param("skillGroupId") Long skillGroupId);

    int countAll();

    int selectPosition(@Param("sessionId") Long sessionId);

    int insert(QueueEntry entry);

    int deleteBySessionId(@Param("sessionId") Long sessionId);
}
