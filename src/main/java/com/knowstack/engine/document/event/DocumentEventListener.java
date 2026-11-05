package com.knowstack.engine.document.event;

import com.knowstack.engine.document.constant.DocumentStatus;
import com.knowstack.engine.document.entity.KnowledgeDocument;
import com.knowstack.engine.document.mapper.KnowledgeDocumentMapper;
import com.knowstack.engine.document.service.DocumentIngestService;
import com.knowstack.engine.document.service.DocumentProcessingLock;
import com.knowstack.engine.document.service.DocumentSplitService;
import com.knowstack.engine.document.service.SegmentEmbeddingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentEventListener {

    private final DocumentIngestService ingestService;
    private final DocumentSplitService splitService;
    private final SegmentEmbeddingService embeddingService;
    private final DocumentProcessingLock processingLock;
    private final KnowledgeDocumentMapper documentMapper;

    @Async
    @TransactionalEventListener(fallbackExecution = true)
    public void onUploaded(DocumentEvents.Uploaded event) {
        log.info("收到文档 UPLOADED 事件 docId={}", event.documentId());
        try {
            ingestService.convert(event.documentId());
        } catch (DocumentProcessingLock.DocumentBusyException e) {
            log.debug("文档正在处理，忽略重复上传事件 docId={}", event.documentId());
        } catch (Exception e) {
            log.error("文档转换失败 docId={}", event.documentId(), e);
        }
    }

    @Async
    @TransactionalEventListener(fallbackExecution = true)
    public void onConverted(DocumentEvents.Converted event) {
        try {
            KnowledgeDocument document = documentMapper.selectById(event.documentId());
            log.info("收到文档 CONVERTED 事件 docId={}, status={}",
                    event.documentId(), document == null ? null : document.getStatus());
            if (document != null && document.getStatus() == DocumentStatus.CONVERTED) {
                log.info("开始文档切分 docId={}", event.documentId());
                splitService.split(event.documentId());
            }
        } catch (DocumentProcessingLock.DocumentBusyException e) {
            log.debug("文档正在处理，忽略重复转换事件 docId={}", event.documentId());
        } catch (Exception e) {
            log.error("文档切分失败 docId={}", event.documentId(), e);
        }
    }

    @Async
    @TransactionalEventListener(fallbackExecution = true)
    public void onChunked(DocumentEvents.Chunked event) {
        try {
            processingLock.execute(event.documentId(), () -> {
                KnowledgeDocument document = documentMapper.selectById(event.documentId());
                log.info("收到文档 CHUNKED 事件 docId={}, status={}",
                        event.documentId(), document == null ? null : document.getStatus());
                if (document != null && document.getStatus() == DocumentStatus.CHUNKED) {
                    log.info("开始文档向量化 docId={}", event.documentId());
                    embeddingService.embed(event.documentId(), document.getCurrentVersionId(), document.getDocTitle());
                }
                return null;
            });
        } catch (DocumentProcessingLock.DocumentBusyException e) {
            log.debug("文档正在处理，忽略重复分段事件 docId={}", event.documentId());
        } catch (Exception e) {
            log.error("文档向量化失败 docId={}", event.documentId(), e);
        }
    }
}
