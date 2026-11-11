package com.knowstack.engine.document.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.knowstack.engine.document.constant.KnowledgeBaseType;
import com.knowstack.engine.document.entity.KnowledgeDocument;
import com.knowstack.engine.document.entity.KnowledgeDocumentVersion;
import com.knowstack.engine.document.entity.KnowledgeSegment;
import com.knowstack.engine.document.mapper.KnowledgeDocumentMapper;
import com.knowstack.engine.document.mapper.KnowledgeDocumentVersionMapper;
import com.knowstack.engine.document.mapper.KnowledgeSegmentMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Objects;

/** 文档历史与跨 MySQL、MinIO、ES 的物理清理。 */
@Service
@RequiredArgsConstructor
public class DocumentHistoryService {
    private final KnowledgeDocumentMapper documents;
    private final KnowledgeDocumentVersionMapper versions;
    private final KnowledgeSegmentMapper segments;
    private final SegmentEmbeddingService embeddings;
    private final MinioService minio;
    private final DocumentProcessingLock lock;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public DocumentPage list(int page, int size) {
        if (page < 1 || size < 1 || size > 100) {
            throw new IllegalArgumentException("page 须大于 0，size 须在 1–100 之间");
        }
        long offset = (long) (page - 1) * size;
        long total = documents.selectCount(null);
        List<KnowledgeDocument> items = documents.selectList(new LambdaQueryWrapper<KnowledgeDocument>()
                .orderByDesc(KnowledgeDocument::getCreatedAt)
                .orderByDesc(KnowledgeDocument::getDocId)
                .last("LIMIT " + size + " OFFSET " + offset));
        return new DocumentPage(items, total, page, size);
    }

    public void delete(Long docId) {
        lock.execute(docId, () -> {
            KnowledgeDocument document = documents.selectById(docId);
            if (document == null) {
                throw new IllegalArgumentException("文档不存在: " + docId);
            }
            List<KnowledgeDocumentVersion> allVersions = versions.selectList(
                    new LambdaQueryWrapper<KnowledgeDocumentVersion>().eq(KnowledgeDocumentVersion::getDocId, docId));
            List<KnowledgeSegment> allSegments = segments.selectList(
                    new LambdaQueryWrapper<KnowledgeSegment>().eq(KnowledgeSegment::getDocumentId, docId));

            // 先清外部资源；任何一步失败时保留文档记录，方便用同一个 ID 重试清理。
            embeddings.removeEmbeddings(allSegments.stream()
                    .filter(s -> !Objects.equals(s.getSkipEmbedding(), 1))
                    .map(s -> s.getEmbeddingId() != null ? s.getEmbeddingId() : s.getChunkId()).toList());
            for (String source : allVersions.stream().map(KnowledgeDocumentVersion::getDocUrl)
                    .filter(Objects::nonNull).distinct().toList()) {
                minio.removeObject(minio.objectName(source));
            }
            minio.removePrefix(docId + "/"); // 各版本转换结果及图片，即使转换中断未写版本 URL 也能清理

            if (document.getKnowledgeBaseType() == KnowledgeBaseType.DATA_QUERY) {
                jdbc.execute("DROP TABLE IF EXISTS `custom_data_query_" + docId + "`");
            }
            transaction.executeWithoutResult(status -> {
                jdbc.update("DELETE tm FROM table_meta tm JOIN knowledge_document_version v "
                        + "ON tm.version_id = v.version_id WHERE v.doc_id = ?", docId);
                jdbc.update("DELETE FROM knowledge_segment WHERE document_id = ?", docId);
                jdbc.update("DELETE FROM knowledge_document_version WHERE doc_id = ?", docId);
                jdbc.update("DELETE FROM knowledge_document WHERE doc_id = ?", docId);
            });
            return null;
        });
    }

    public record DocumentPage(List<KnowledgeDocument> items, long total, int page, int size) { }
}
