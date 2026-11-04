package com.knowstack.engine.rag.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.elasticsearch.ElasticsearchEmbeddingStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 向量存储配置：ES dense_vector 索引，首次写入时按 dimensions 自动建 mapping
 */
@Configuration
public class VectorStoreConfig {

    @Bean
    public EmbeddingStore<TextSegment> embeddingStore(
            @Value("${spring.elasticsearch.uris}") String serverUrl,
            @Value("${vector-store.index}") String indexName,
            @Value("${embedding.dimensions}") int dimensions) {
        return ElasticsearchEmbeddingStore.builder()
                .serverUrl(serverUrl)
                .indexName(indexName)
                .dimension(dimensions)
                .build();
    }
}
