package com.knowstack.engine.document.service;

import com.alibaba.fastjson2.JSON;
import com.knowstack.engine.document.constant.DocumentStatus;
import com.knowstack.engine.document.entity.DocumentSplitParam;
import com.knowstack.engine.document.entity.KnowledgeDocument;
import com.knowstack.engine.document.entity.KnowledgeDocumentVersion;
import com.knowstack.engine.document.entity.KnowledgeSegment;
import com.knowstack.engine.document.mapper.KnowledgeDocumentMapper;
import com.knowstack.engine.document.mapper.KnowledgeDocumentVersionMapper;
import com.knowstack.engine.document.mapper.KnowledgeSegmentMapper;
import com.knowstack.engine.rag.modules.splitter.MarkdownHeaderParentTextSplitter;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChunkIdentityTest {

    @Test
    void repeatedSplitsKeepParentChildIdsDistinctAndResolvable() {
        MarkdownHeaderParentTextSplitter splitter = new MarkdownHeaderParentTextSplitter(20, 4);
        Document document = Document.from("# A\nThe first section is long enough to be split.\n# B\nA second section is here.");
        List<TextSegment> first = splitter.split(document);
        List<TextSegment> second = splitter.split(document);
        Set<String> ids = new HashSet<>();
        for (List<TextSegment> split : List.of(first, second)) {
            for (TextSegment segment : split) {
                String id = segment.metadata().getString("chunkId");
                assertThat(id).matches("\\d+");
                assertThat(ids.add(id)).isTrue();
                String parentId = segment.metadata().getString("parentChunkId");
                if (parentId != null) {
                    assertThat(split).anySatisfy(parent -> {
                        assertThat(parent.metadata().getString("chunkId")).isEqualTo(parentId);
                        assertThat(parent.metadata().getInteger("skipEmbedding")).isEqualTo(1);
                    });
                }
            }
        }
    }

    @Test
    void titleOnlyModeDoesNotRouteToMissingParents() {
        MarkdownHeaderParentTextSplitter splitter = new MarkdownHeaderParentTextSplitter(3, false, false, 40, 4);
        List<TextSegment> children = splitter.split(Document.from("# A\nThis child has searchable content."));
        assertThat(children).isNotEmpty().allSatisfy(child -> {
            assertThat(child.metadata().getString("chunkId")).matches("\\d+");
            assertThat(child.metadata().getString("parentChunkId")).isNull();
            assertThat(child.metadata().getInteger("skipEmbedding")).isZero();
        });
    }

    @Test
    void ordinarySplitterSegmentsReceivePersistedSnowflakeIds() {
        KnowledgeDocument document = new KnowledgeDocument();
        document.setDocId(7L);
        document.setStatus(DocumentStatus.CONVERTED);
        document.setCurrentVersionId(1L);
        document.setSplitParam(new DocumentSplitParam("LENGTH", 20, 4, null, null, null));
        KnowledgeDocumentVersion version = new KnowledgeDocumentVersion();
        version.setVersionId(1L);
        version.setConvertedDocUrl("converted-url");
        KnowledgeDocumentMapper documents = mock(KnowledgeDocumentMapper.class);
        KnowledgeDocumentVersionMapper versions = mock(KnowledgeDocumentVersionMapper.class);
        KnowledgeSegmentMapper segments = mock(KnowledgeSegmentMapper.class);
        MinioService minio = mock(MinioService.class);
        DocumentProcessingLock lock = mock(DocumentProcessingLock.class);
        TransactionTemplate transaction = mock(TransactionTemplate.class);
        List<KnowledgeSegment> saved = new ArrayList<>();
        when(documents.selectById(7L)).thenReturn(document);
        when(versions.selectById(1L)).thenReturn(version);
        when(minio.objectName("converted-url")).thenReturn("converted");
        when(minio.downloadText("converted")).thenReturn("Alpha beta gamma delta epsilon zeta eta theta iota.");
        when(segments.selectList(any())).thenReturn(List.of());
        when(segments.insert(any(KnowledgeSegment.class))).thenAnswer(call -> {
            saved.add(call.getArgument(0));
            return 1;
        });
        when(lock.execute(eq(7L), any())).thenAnswer(call -> call.<Callable<?>>getArgument(1).call());
        when(transaction.execute(any(TransactionCallback.class))).thenAnswer(call ->
                call.<TransactionCallback<?>>getArgument(0).doInTransaction(mock(TransactionStatus.class)));
        DocumentSplitService service = new DocumentSplitService(minio, mock(SegmentEmbeddingService.class),
                mock(ApplicationEventPublisher.class), documents, versions, segments, lock, transaction);

        assertThat(service.split(7L)).isEqualTo(saved.size()).isGreaterThan(1);
        assertThat(saved).extracting(KnowledgeSegment::getChunkId).doesNotHaveDuplicates();
        assertThat(saved).allSatisfy(segment -> {
            assertThat(segment.getChunkId()).matches("\\d+");
            assertThat(JSON.parseObject(segment.getMetadata()).getString("chunkId")).isEqualTo(segment.getChunkId());
        });
    }

}
