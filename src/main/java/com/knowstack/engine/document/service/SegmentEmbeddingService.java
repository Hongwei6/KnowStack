package com.knowstack.engine.document.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.knowstack.engine.document.constant.DocumentStatus;
import com.knowstack.engine.document.constant.SegmentStatus;
import com.knowstack.engine.document.entity.KnowledgeDocument;
import com.knowstack.engine.document.entity.KnowledgeDocumentVersion;
import com.knowstack.engine.document.entity.KnowledgeSegment;
import com.knowstack.engine.document.mapper.KnowledgeDocumentMapper;
import com.knowstack.engine.document.mapper.KnowledgeDocumentVersionMapper;
import com.knowstack.engine.document.mapper.KnowledgeSegmentMapper;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 分段向量化服务：对 skip_embedding=0 的子分段做批量 embedding 并写入向量库，
 * 文档状态推进到 VECTOR_STORED；重切分前按 embeddingId 清理旧向量
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SegmentEmbeddingService {

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeDocumentVersionMapper versionMapper;
    private final KnowledgeSegmentMapper segmentMapper;

    /**
     * 对文档全部子分段（skip_embedding=0）做嵌入并写向量库，chunkId 作为向量 ID
     */
    public void embed(Long docId, Long versionId, String docTitle) {
        List<KnowledgeSegment> segments = segmentMapper.selectList(new LambdaQueryWrapper<KnowledgeSegment>()
                .eq(KnowledgeSegment::getDocumentId, docId)
                .eq(KnowledgeSegment::getSkipEmbedding, 0));
        if (segments.isEmpty()) {
            log.warn("文档 docId={} 没有可嵌入的子分段", docId);
        }

        List<String> ids = new ArrayList<>(segments.size());
        List<TextSegment> toEmbed = new ArrayList<>(segments.size());
        for (KnowledgeSegment segment : segments) {
            Metadata metadata = new Metadata();
            metadata.put("chunkId", segment.getChunkId());
            metadata.put("documentId", docId);
            metadata.put("docTitle", docTitle);
            metadata.put("chunkOrder", segment.getChunkOrder());
            ids.add(segment.getChunkId());
            toEmbed.add(TextSegment.from(segment.getText(), metadata));
        }
        Response<List<Embedding>> embeddingResponse = embeddingModel.embedAll(toEmbed);
        embeddingStore.addAll(ids, embeddingResponse.content(), toEmbed);

        for (KnowledgeSegment segment : segments) {
            segment.setEmbeddingId(segment.getChunkId());
            segment.setStatus(SegmentStatus.VECTOR_STORED);
            segmentMapper.updateById(segment);
        }

        KnowledgeDocument document = new KnowledgeDocument();
        document.setDocId(docId);
        document.setStatus(DocumentStatus.VECTOR_STORED);
        documentMapper.updateById(document);
        KnowledgeDocumentVersion version = new KnowledgeDocumentVersion();
        version.setVersionId(versionId);
        version.setStatus(DocumentStatus.VECTOR_STORED);
        versionMapper.updateById(version);
        log.info("文档 docId={} 向量入库完成，共 {} 个子分段", docId, segments.size());
    }

    /**
     * 重切分前清理旧向量，避免向量库孤儿数据（忽略 null 的 embeddingId，父分段没有）
     */
    public void removeEmbeddings(Collection<String> embeddingIds) {
        List<String> ids = embeddingIds == null ? null : embeddingIds.stream()
                .filter(java.util.Objects::nonNull).toList();
        if (ids != null && !ids.isEmpty()) {
            embeddingStore.removeAll(ids);
            log.info("已清理旧向量 {} 条", ids.size());
        }
    }
}
