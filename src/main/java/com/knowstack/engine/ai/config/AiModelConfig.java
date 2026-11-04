package com.knowstack.engine.ai.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class AiModelConfig {

    @Bean
    public EmbeddingModel embeddingModel(
            @Value("${embedding.base-url}") String baseUrl,
            @Value("${embedding.model}") String model,
            @Value("${embedding.dimensions}") int dimensions) {
        return OpenAiEmbeddingModel.builder()
                .baseUrl(baseUrl + "/v1")
                .apiKey("ollama")
                .modelName(model)
                .dimensions(dimensions)
                .build();
    }

    @Bean
    public ChatModel chatModel(
            @Value("${deepseek.base-url}") String baseUrl,
            @Value("${deepseek.model}") String model,
            @Value("${deepseek.api-key}") String apiKey) {
        return OpenAiChatModel.builder().baseUrl(baseUrl).apiKey(apiKey).modelName(model).build();
    }

    @Bean
    public StreamingChatModel streamingChatModel(
            @Value("${deepseek.base-url}") String baseUrl,
            @Value("${deepseek.model}") String model,
            @Value("${deepseek.api-key}") String apiKey) {
        return OpenAiStreamingChatModel.builder().baseUrl(baseUrl).apiKey(apiKey).modelName(model).build();
    }

    /**
     * 视觉模型（Yoga Ollama qwen3-vl），用于生成 Markdown 中图片的描述
     */
    @Bean
    public ChatModel visionChatModel(
            @Value("${vision.base-url}") String baseUrl,
            @Value("${vision.model}") String model) {
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl + "/v1")
                .apiKey("ollama")
                .modelName(model)
                .maxTokens(2048)
                .timeout(Duration.ofMinutes(3))
                .build();
    }
}
