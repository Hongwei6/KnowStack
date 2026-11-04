package com.knowstack.engine.rag.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.elasticsearch.ElasticsearchEmbeddingStore;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** ES 客户端与向量存储共享 application.yml 中的连接和索引。 */
@Configuration
public class ElasticSearchConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(RestClient.class)
    public RestClient restClient(@Value("${spring.elasticsearch.uris}") String serverUrl) {
        return RestClient.builder(HttpHost.create(serverUrl)).build();
    }

    @Bean
    public EmbeddingStore<TextSegment> embeddingStore(
            RestClient restClient,
            @Value("${vector-store.index}") String indexName) {
        return ElasticsearchEmbeddingStore.builder()
                .restClient(restClient)
                .indexName(indexName)
                .build();
    }
}
