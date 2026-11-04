package com.knowstack.engine.document.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.knowstack.engine.document.constant.DocumentStatus;
import com.knowstack.engine.document.constant.KnowledgeBaseType;
import com.knowstack.engine.document.entity.KnowledgeDocument;
import com.knowstack.engine.document.event.DocumentEvents;
import com.knowstack.engine.document.mapper.KnowledgeDocumentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/** 用持久化状态补发进程重启、事件丢失或阶段失败后的工作。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentStageRecoveryJob {

    private final KnowledgeDocumentMapper documentMapper;
    private final ApplicationEventPublisher events;

    @XxlJob("documentStageRecovery")
    public void recover() {
        LocalDateTime now = LocalDateTime.now();
        Long lastId = null;
        List<KnowledgeDocument> documents;
        do {
            LambdaQueryWrapper<KnowledgeDocument> query = new LambdaQueryWrapper<KnowledgeDocument>()
                    .and(status -> status.in(KnowledgeDocument::getStatus, DocumentStatus.UPLOADED,
                                    DocumentStatus.CONVERTED, DocumentStatus.CHUNKED)
                            .lt(KnowledgeDocument::getUpdatedAt, now.minusMinutes(1))
                            .or().eq(KnowledgeDocument::getStatus, DocumentStatus.CONVERTING)
                            .lt(KnowledgeDocument::getUpdatedAt, now.minusMinutes(15)))
                    .and(type -> type.isNull(KnowledgeDocument::getKnowledgeBaseType)
                            .or().ne(KnowledgeDocument::getKnowledgeBaseType, KnowledgeBaseType.DATA_QUERY))
                    .orderByAsc(KnowledgeDocument::getDocId)
                    .last("LIMIT 100");
            if (lastId != null) {
                query.gt(KnowledgeDocument::getDocId, lastId);
            }
            documents = documentMapper.selectList(query);
            for (KnowledgeDocument document : documents) {
                log.info("补发文档阶段事件 docId={}, status={}", document.getDocId(), document.getStatus());
                switch (document.getStatus()) {
                    case UPLOADED, CONVERTING -> events.publishEvent(new DocumentEvents.Uploaded(document.getDocId()));
                    case CONVERTED -> events.publishEvent(new DocumentEvents.Converted(document.getDocId()));
                    case CHUNKED -> events.publishEvent(new DocumentEvents.Chunked(document.getDocId()));
                    default -> { }
                }
            }
            if (!documents.isEmpty()) {
                lastId = documents.getLast().getDocId();
            }
        } while (documents.size() == 100);
    }
}
