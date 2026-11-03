package com.knowstack.engine.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("chat_message")
public class ChatMessage {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String messageId;
    private String conversationId;
    private String type;
    private String content;
    private String transformContent;
    private Integer tokenCount;
    private String modelName;
    private String ragReferences;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @Version
    private Integer lockVersion;
    @TableLogic
    private Integer deleted;
    private String metadata;
    private Integer cacheHit;
    private Integer helpful;
    private String feedbackComment;
    private LocalDateTime feedbackAt;
}
