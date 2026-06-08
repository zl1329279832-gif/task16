package com.customerservice.mapper;

import com.customerservice.model.entity.ChatMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface MessageMapper {

    ChatMessage selectById(@Param("id") Long id);

    ChatMessage selectByMessageUid(@Param("messageUid") String messageUid);

    List<ChatMessage> selectBySessionId(@Param("sessionId") Long sessionId);

    List<ChatMessage> selectBySessionIdAfterSeq(@Param("sessionId") Long sessionId,
                                                 @Param("afterSeq") Long afterSeq);

    Long selectMaxSequenceNo(@Param("sessionId") Long sessionId);

    int insert(ChatMessage message);

    int updateStatus(@Param("id") Long id, @Param("status") String status);
}
