package com.knowstack.engine.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.knowstack.engine.chat.entity.ChatMessage;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {
}
