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
    void updateRisk(@Param("sessionId") Long sessionId, @Param("riskScore") int riskScore,
                    @Param("riskLevel") String riskLevel);
    void updateSlaDeadline(@Param("sessionId") Long sessionId,
                           @Param("slaDeadline") java.time.LocalDateTime slaDeadline);
    void updatePinned(@Param("sessionId") Long sessionId, @Param("pinned") boolean pinned,
                      @Param("pinnedBy") String pinnedBy);
    void updateSkillGroupId(@Param("sessionId") Long sessionId,
                            @Param("skillGroupId") Long skillGroupId,
                            @Param("originalSkillGroupId") Long originalSkillGroupId);
    List<QueueEntry> selectByRiskLevel(@Param("riskLevel") String riskLevel);
    List<QueueEntry> selectPinned();
}
