package com.knowstack.engine.document.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.knowstack.engine.document.constant.DocumentStatus;
import com.knowstack.engine.document.constant.SegmentStatus;
import com.knowstack.engine.document.constant.SplitPreset;
import com.knowstack.engine.document.entity.KnowledgeDocument;
import com.knowstack.engine.document.entity.KnowledgeDocumentVersion;
import com.knowstack.engine.document.entity.KnowledgeSegment;
import com.knowstack.engine.document.entity.DocumentSplitParam;
import com.knowstack.engine.document.event.DocumentEvents;
import com.knowstack.engine.document.mapper.KnowledgeDocumentMapper;
import com.knowstack.engine.document.mapper.KnowledgeDocumentVersionMapper;
import com.knowstack.engine.document.mapper.KnowledgeSegmentMapper;
import com.knowstack.engine.rag.modules.splitter.DocumentSplitterFactory;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.segment.TextSegment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.Assert;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档切分服务：从 MinIO 下载转换后的 Markdown，按文档保存的 DocumentSplitParam
 * （无则用 SplitPreset.DEFAULT 预设）经 DocumentSplitterFactory 选分片器切分，
 * 按顺序保存 knowledge_segment（后台分段预览的数据来源），文档推进到 CHUNKED
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentSplitService {

    private final MinioService minioService;
    private final SegmentEmbeddingService embeddingService;
    private final ApplicationEventPublisher events;
    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeDocumentVersionMapper versionMapper;
    private final KnowledgeSegmentMapper segmentMapper;
    private final DocumentProcessingLock processingLock;
    private final TransactionTemplate transactionTemplate;

    /**
     * 按已保存参数切分；已切分/已向量化时幂等返回现有子分段数量
     */
    public int split(Long documentId) {
        return split(documentId, null);
    }

    /**
     * 切分文档；传入 override 时更新文档切分参数并强制重切——
     * 旧分段已向量化的先清理向量库，重切后自动重新嵌入
     *
     * @return 保存的分段总数
     */
    public int split(Long documentId, DocumentSplitParam override) {
        int count = processingLock.execute(documentId,
                () -> transactionTemplate.execute(status -> doSplit(documentId, override)));
        log.info("发布文档 CHUNKED 事件 docId={}, segmentCount={}", documentId, count);
        events.publishEvent(new DocumentEvents.Chunked(documentId));
        return count;
    }

    private int doSplit(Long documentId, DocumentSplitParam override) {
        KnowledgeDocument document = documentMapper.selectById(documentId);
        Assert.notNull(document, "文档不存在");

        // 传新参数 → 保存并强制重切；否则已切分（含已向量化）幂等返回子分段数量
        boolean forced = false;
        if (override != null) {
            document.setSplitParam(override);
            documentMapper.updateById(document);
            forced = true;
        }
        if (!forced && (document.getStatus() == DocumentStatus.CHUNKED
                || document.getStatus() == DocumentStatus.VECTOR_STORED)) {
            Long chunkedCount = segmentMapper.selectCount(new LambdaQueryWrapper<KnowledgeSegment>()
                    .eq(KnowledgeSegment::getDocumentId, documentId)
                    .eq(KnowledgeSegment::getSkipEmbedding, 0));
            return chunkedCount.intValue();
        }
        Assert.isTrue(forced || document.getStatus() == DocumentStatus.CONVERTED,
                "文档状态不为CONVERTED，无法完成切分");
        Assert.isTrue(document.getStatus() == DocumentStatus.CONVERTED
                || document.getStatus() == DocumentStatus.CHUNKED
                || document.getStatus() == DocumentStatus.VECTOR_STORED, "文档未转换完成，无法重切");

        // 1. 重切前：旧分段已向量化的，先清理向量库
        List<KnowledgeSegment> oldSegments = segmentMapper.selectList(new LambdaQueryWrapper<KnowledgeSegment>()
                .eq(KnowledgeSegment::getDocumentId, documentId));
        boolean wasVectorized = oldSegments.stream().anyMatch(s -> s.getEmbeddingId() != null);
        if (wasVectorized) {
            embeddingService.removeEmbeddings(oldSegments.stream()
                    .map(KnowledgeSegment::getEmbeddingId).toList());
        }

        // 2. 从版本记录取转换后的 Markdown 并下载
        KnowledgeDocumentVersion version = versionMapper.selectById(document.getCurrentVersionId());
        Assert.notNull(version, "文档版本记录不存在");
        Assert.notNull(version.getConvertedDocUrl(), "文档未转换完成");
        String content = minioService.downloadText(minioService.objectName(version.getConvertedDocUrl()));

        // 3. 按文档保存的参数选分片器（无则默认预设），父子分片
        DocumentSplitParam param = document.getSplitParam() != null
                ? document.getSplitParam() : SplitPreset.DEFAULT.toParam();
        DocumentSplitter splitter = DocumentSplitterFactory.getInstance(param);
        Assert.notNull(splitter, "不支持的切分类型: " + param.splitType());
        List<TextSegment> segments = splitter.split(Document.from(content));

        // 4. 清理旧分段后按顺序保存
        segmentMapper.delete(new LambdaQueryWrapper<KnowledgeSegment>()
                .eq(KnowledgeSegment::getDocumentId, documentId));
        for (int i = 0; i < segments.size(); i++) {
            TextSegment segment = segments.get(i);
            Map<String, Object> metadataMap = new LinkedHashMap<>();
            metadataMap.put("chunkId", segment.metadata().getString("chunkId"));
            String parentChunkId = segment.metadata().getString("parentChunkId");
            if (parentChunkId != null) {
                metadataMap.put("parentChunkId", parentChunkId);
            }
            Integer skipEmbedding = segment.metadata().getInteger("skipEmbedding");

            KnowledgeSegment knowledgeSegment = new KnowledgeSegment();
            knowledgeSegment.setText(segment.text());
            knowledgeSegment.setChunkId(segment.metadata().getString("chunkId"));
            knowledgeSegment.setMetadata(JSON.toJSONString(metadataMap));
            knowledgeSegment.setDocumentId(documentId);
            knowledgeSegment.setDocumentVersion(version.getVersionId());
            knowledgeSegment.setChunkOrder(i);
            knowledgeSegment.setStatus(SegmentStatus.INIT);
            knowledgeSegment.setSkipEmbedding(skipEmbedding != null && skipEmbedding == 1 ? 1 : 0);
            segmentMapper.insert(knowledgeSegment);
        }

        // 5. 文档与版本推进到 CHUNKED
        document.setStatus(DocumentStatus.CHUNKED);
        documentMapper.updateById(document);
        version.setStatus(DocumentStatus.CHUNKED);
        versionMapper.updateById(version);
        log.info("文档 docId={} 切分完成，共 {} 段，参数: chunkSize={}, overlap={}, type={}",
                documentId, segments.size(), param.chunkSize(), param.overlap(), param.splitType());

        // 向量化由提交后的 CHUNKED 事件驱动
        return segments.size();
    }
}
