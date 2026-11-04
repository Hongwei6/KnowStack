package com.knowstack.engine.document.service;

import dev.langchain4j.data.image.Image;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Base64;

/**
 * 视觉模型服务：用 Yoga Ollama 的 qwen3-vl 生成图片描述，
 * 写入 Markdown 图片 alt，使图片内容可被检索
 */
@Slf4j
@Service
public class VisionService {

    private static final String PROMPT = "请描述这张图片的内容，包括场景、对象、布局、颜色、文字信息，直接输出纯文本描述，不要多余说明。";
    private static final String DEFAULT_ALT = "图片";

    private final ChatModel visionChatModel;

    public VisionService(@Qualifier("visionChatModel") ChatModel visionChatModel) {
        this.visionChatModel = visionChatModel;
    }

    /**
     * 生成图片描述；失败时降级为默认 alt，不阻断文档处理流程
     */
    public String describe(byte[] imageBytes, String mimeType) {
        try {
            Image image = Image.builder()
                    .base64Data(Base64.getEncoder().encodeToString(imageBytes))
                    .mimeType(mimeType)
                    .build();
            String text = visionChatModel.chat(ChatRequest.builder()
                            .messages(UserMessage.from(TextContent.from(PROMPT), new ImageContent(image)))
                            .build())
                    .aiMessage().text();
            if (text != null && !text.isBlank()) {
                return text.replace("\r", " ").replace("\n", " ").trim();
            }
        } catch (Exception e) {
            log.warn("图片描述生成失败，使用默认 alt: {}", e.getMessage());
        }
        return DEFAULT_ALT;
    }
}
