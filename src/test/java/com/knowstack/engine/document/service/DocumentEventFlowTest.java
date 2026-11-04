package com.knowstack.engine.document.service;

import com.knowstack.engine.document.constant.DocumentStatus;
import com.knowstack.engine.document.entity.KnowledgeDocument;
import com.knowstack.engine.document.entity.KnowledgeDocumentVersion;
import com.knowstack.engine.document.entity.KnowledgeSegment;
import com.knowstack.engine.document.event.DocumentEventListener;
import com.knowstack.engine.document.event.DocumentEvents;
import com.knowstack.engine.document.job.DocumentStageRecoveryJob;
import com.knowstack.engine.document.mapper.KnowledgeDocumentMapper;
import com.knowstack.engine.document.mapper.KnowledgeDocumentVersionMapper;
import com.knowstack.engine.document.mapper.KnowledgeSegmentMapper;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentEventFlowTest {

    @Test
    void failedConversionAndLostEventRecoverFromPersistedDocumentStage() throws Exception {
        KnowledgeDocument document = new KnowledgeDocument();
        document.setDocId(7L);
        document.setCurrentVersionId(1L);
        document.setDocTitle("example.pdf");
        document.setStatus(DocumentStatus.UPLOADED);
        KnowledgeDocumentVersion version = new KnowledgeDocumentVersion();
        version.setVersionId(1L);
        version.setDocUrl("source-url");
        KnowledgeDocumentMapper documents = mock(KnowledgeDocumentMapper.class);
        KnowledgeDocumentVersionMapper versions = mock(KnowledgeDocumentVersionMapper.class);
        KnowledgeSegmentMapper segments = mock(KnowledgeSegmentMapper.class);
        MinioService minio = mock(MinioService.class);
        MineruClient mineru = mock(MineruClient.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        DocumentProcessingLock lock = mock(DocumentProcessingLock.class);
        TransactionTemplate transaction = mock(TransactionTemplate.class);
        EmbeddingModel model = mock(EmbeddingModel.class);
        @SuppressWarnings("unchecked")
        EmbeddingStore<TextSegment> store = mock(EmbeddingStore.class);
        List<KnowledgeSegment> saved = new ArrayList<>();
        String markdown = "# Topic\nA useful fact about event-driven documents.";
        AtomicBoolean mineruAvailable = new AtomicBoolean(false);
        AtomicBoolean loseFirstConvertedEvent = new AtomicBoolean(true);
        AtomicReference<DocumentEventListener> listener = new AtomicReference<>();

        when(documents.selectById(7L)).thenReturn(document);
        when(versions.selectById(1L)).thenReturn(version);
        when(documents.updateById(any(KnowledgeDocument.class))).thenAnswer(invocation -> {
            document.setStatus(invocation.<KnowledgeDocument>getArgument(0).getStatus());
            return 1;
        });
        when(versions.updateById(any(KnowledgeDocumentVersion.class))).thenAnswer(invocation -> {
            version.setStatus(invocation.<KnowledgeDocumentVersion>getArgument(0).getStatus());
            if (invocation.<KnowledgeDocumentVersion>getArgument(0).getConvertedDocUrl() != null) {
                version.setConvertedDocUrl(invocation.<KnowledgeDocumentVersion>getArgument(0).getConvertedDocUrl());
            }
            return 1;
        });
        when(minio.objectName("source-url")).thenReturn("source");
        when(minio.objectName("converted-url")).thenReturn("converted");
        when(minio.downloadBytes("source")).thenReturn(new byte[]{1});
        when(minio.downloadText("converted")).thenReturn(markdown);
        when(minio.upload(anyString(), any(byte[].class), eq("text/markdown"))).thenReturn("converted-url");
        when(mineru.parseToZip(eq("example.pdf"), any(byte[].class))).thenAnswer(invocation -> {
            if (!mineruAvailable.get()) {
                throw new IllegalStateException("MinerU 暂不可用");
            }
            return markdownZip(markdown);
        });
        when(lock.execute(eq(7L), any())).thenAnswer(invocation -> invocation.<Callable<?>>getArgument(1).call());
        when(transaction.execute(any(TransactionCallback.class))).thenAnswer(invocation ->
                invocation.<TransactionCallback<?>>getArgument(0).doInTransaction(mock(TransactionStatus.class)));
        when(segments.selectList(any())).thenAnswer(invocation -> new ArrayList<>(saved));
        when(segments.insert(any(KnowledgeSegment.class))).thenAnswer(invocation -> {
            saved.add(invocation.getArgument(0));
            return 1;
        });
        when(model.embedAll(anyList())).thenAnswer(invocation -> {
            List<TextSegment> inputs = invocation.getArgument(0);
            return Response.from(inputs.stream().map(input -> Embedding.from(new float[]{1.0f})).toList());
        });
        doAnswer(invocation -> {
            Object event = invocation.getArgument(0);
            if (event instanceof DocumentEvents.Converted converted && !loseFirstConvertedEvent.getAndSet(false)) {
                listener.get().onConverted(converted);
            }
            if (event instanceof DocumentEvents.Chunked chunked) listener.get().onChunked(chunked);
            return null;
        }).when(events).publishEvent(any(Object.class));

        SegmentEmbeddingService embedding = new SegmentEmbeddingService(model, store, documents, versions, segments);
        DocumentSplitService split = new DocumentSplitService(minio, embedding, events, documents,
                versions, segments, lock, transaction);
        DocumentIngestService ingest = new DocumentIngestService(mineru, minio, mock(VisionService.class),
                events, lock, documents, versions);
        listener.set(new DocumentEventListener(ingest, split, embedding, lock, documents));

        listener.get().onUploaded(new DocumentEvents.Uploaded(7L));
        assertThat(document.getStatus()).isEqualTo(DocumentStatus.CONVERTING);
        assertThat(saved).isEmpty();

        mineruAvailable.set(true);
        listener.get().onUploaded(new DocumentEvents.Uploaded(7L));
        assertThat(document.getStatus()).isEqualTo(DocumentStatus.CONVERTED);
        assertThat(saved).isEmpty();
        when(documents.selectList(any())).thenReturn(List.of(document));
        new DocumentStageRecoveryJob(documents, events).recover();
        assertThat(document.getStatus()).isEqualTo(DocumentStatus.VECTOR_STORED);
        assertThat(version.getStatus()).isEqualTo(DocumentStatus.VECTOR_STORED);
        assertThat(saved).anyMatch(segment -> segment.getSkipEmbedding() == 0 && segment.getEmbeddingId() != null);
        verify(store).addAll(anyList(), anyList(), anyList());
        listener.get().onUploaded(new DocumentEvents.Uploaded(7L));
        verify(mineru, times(2)).parseToZip(eq("example.pdf"), any(byte[].class));
        verify(minio, times(2)).downloadBytes("source");
    }

    private static byte[] markdownZip(String markdown) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("example.md"));
            zip.write(markdown.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return out.toByteArray();
    }
}
