package com.cs.alloc.mapper;

import com.cs.alloc.domain.QueueEntry;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface QueueEntryMapper {
    void insert(QueueEntry entry);
    int insertIgnore(QueueEntry entry);
    QueueEntry selectBySessionId(@Param("sessionId") Long sessionId);
    List<QueueEntry> selectBySkillGroupId(@Param("skillGroupId") Long skillGroupId);
    List<QueueEntry> selectAll();
    void deleteBySessionId(@Param("sessionId") Long sessionId);
    void updatePosition(@Param("sessionId") Long sessionId, @Param("position") int position);
    void updatePriorityScore(@Param("sessionId") Long sessionId, @Param("priorityScore") int score);
    int countBySkillGroupId(@Param("skillGroupId") Long skillGroupId);
    void updatePinned(@Param("sessionId") Long sessionId, @Param("pinned") boolean pinned);
    void updateSkillGroupId(@Param("sessionId") Long sessionId, @Param("skillGroupId") Long skillGroupId, @Param("originalSkillGroupId") Long originalSkillGroupId);
    int batchUpdateSkillGroup(@Param("oldSkillGroupId") Long oldSkillGroupId, @Param("newSkillGroupId") Long newSkillGroupId);
    List<QueueEntry> selectByOriginalSkillGroupId(@Param("originalSkillGroupId") Long originalSkillGroupId);
}
