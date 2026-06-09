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

    /**
     * 只查询关联 session 状态为 WAITING 的排队条目 (JOIN session)。
     */
    List<QueueEntry> selectWaitingBySkillGroupId(@Param("skillGroupId") Long skillGroupId);

    /**
     * 只批量更新关联 session 状态为 WAITING 的条目的技能组。
     */
    int batchUpdateSkillGroupWaiting(@Param("oldSkillGroupId") Long oldSkillGroupId, @Param("newSkillGroupId") Long newSkillGroupId);
}
