package com.knowstack.engine.document.service;

import com.knowstack.engine.document.constant.KnowledgeBaseType;
import com.knowstack.engine.document.entity.KnowledgeDocument;
import com.knowstack.engine.document.entity.KnowledgeDocumentVersion;
import com.knowstack.engine.document.entity.KnowledgeSegment;
import com.knowstack.engine.document.mapper.KnowledgeDocumentMapper;
import com.knowstack.engine.document.mapper.KnowledgeDocumentVersionMapper;
import com.knowstack.engine.document.mapper.KnowledgeSegmentMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class DocumentHistoryServiceTest {
    private final KnowledgeDocumentMapper documents = mock(KnowledgeDocumentMapper.class);
    private final KnowledgeDocumentVersionMapper versions = mock(KnowledgeDocumentVersionMapper.class);
    private final KnowledgeSegmentMapper segments = mock(KnowledgeSegmentMapper.class);
    private final SegmentEmbeddingService embeddings = mock(SegmentEmbeddingService.class);
    private final MinioService minio = mock(MinioService.class);
    private final DocumentProcessingLock lock = mock(DocumentProcessingLock.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final TransactionTemplate transaction = mock(TransactionTemplate.class);
    private final DocumentHistoryService service = new DocumentHistoryService(
            documents, versions, segments, embeddings, minio, lock, jdbc, transaction);

    @Test
    void deletesAllVersionsAndGeneratedResourcesPhysically() throws Exception {
        when(lock.execute(eq(42L), any())).thenAnswer(call -> call.<Callable<Void>>getArgument(1).call());
        doAnswer(call -> {
            call.<Consumer<TransactionStatus>>getArgument(0).accept(mock(TransactionStatus.class));
            return null;
        }).when(transaction).executeWithoutResult(any());
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setKnowledgeBaseType(KnowledgeBaseType.DATA_QUERY);
        when(documents.selectById(42L)).thenReturn(doc);
        KnowledgeDocumentVersion first = new KnowledgeDocumentVersion();
        first.setDocUrl("source/one");
        KnowledgeDocumentVersion second = new KnowledgeDocumentVersion();
        second.setDocUrl("source/two");
        when(versions.selectList(any())).thenReturn(List.of(first, second));
        when(minio.objectName("source/one")).thenReturn("key/one");
        when(minio.objectName("source/two")).thenReturn("key/two");
        KnowledgeSegment embedded = new KnowledgeSegment();
        embedded.setChunkId("child-a");
        embedded.setEmbeddingId("child-a");
        embedded.setSkipEmbedding(0);
        KnowledgeSegment partial = new KnowledgeSegment();
        partial.setChunkId("child-b");
        partial.setSkipEmbedding(0);
        KnowledgeSegment parent = new KnowledgeSegment();
        parent.setChunkId("parent");
        parent.setSkipEmbedding(1);
        when(segments.selectList(any())).thenReturn(List.of(embedded, partial, parent));

        service.delete(42L);

        verify(embeddings).removeEmbeddings(List.of("child-a", "child-b"));
        verify(minio).removeObject("key/one");
        verify(minio).removeObject("key/two");
        verify(minio).removePrefix("42/");
        verify(jdbc).execute("DROP TABLE IF EXISTS `custom_data_query_42`");
        verify(jdbc).update(eq("DELETE tm FROM table_meta tm JOIN knowledge_document_version v "
                + "ON tm.version_id = v.version_id WHERE v.doc_id = ?"), eq(42L));
        verify(jdbc).update("DELETE FROM knowledge_segment WHERE document_id = ?", 42L);
        verify(jdbc).update("DELETE FROM knowledge_document_version WHERE doc_id = ?", 42L);
        verify(jdbc).update("DELETE FROM knowledge_document WHERE doc_id = ?", 42L);
    }

    @Test
    void leavesDatabaseRecordsWhenExternalCleanupFailsSoDeletionCanBeRetried() throws Exception {
        when(lock.execute(eq(42L), any())).thenAnswer(call -> call.<Callable<Void>>getArgument(1).call());
        when(documents.selectById(42L)).thenReturn(new KnowledgeDocument());
        when(versions.selectList(any())).thenReturn(List.of());
        when(segments.selectList(any())).thenReturn(List.of());
        doThrow(new IllegalStateException("storage unavailable")).when(minio).removePrefix("42/");

        assertThatThrownBy(() -> service.delete(42L))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("storage unavailable");
        verifyNoInteractions(jdbc, transaction);
    }
}
