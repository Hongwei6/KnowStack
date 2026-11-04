package com.knowstack.engine.document.service;

import com.knowstack.engine.document.constant.DocumentStatus;
import com.knowstack.engine.document.controller.DocumentController;
import com.knowstack.engine.document.entity.KnowledgeDocumentVersion;
import com.knowstack.engine.document.event.DocumentEventListener;
import com.knowstack.engine.document.event.DocumentEvents;
import com.knowstack.engine.document.job.DocumentStageRecoveryJob;
import com.knowstack.engine.document.entity.KnowledgeDocument;
import com.knowstack.engine.document.mapper.KnowledgeDocumentMapper;
import com.knowstack.engine.document.mapper.KnowledgeDocumentVersionMapper;
import com.knowstack.engine.document.mapper.KnowledgeSegmentMapper;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DocumentProcessingLockTest {

    private final DocumentProcessingLock processingLock = new DocumentProcessingLock(lockClient());

    @Test
    void serializesSameDocumentButNotOtherDocumentsAndReleasesAfterFailure() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> first = executor.submit(() -> assertThatThrownBy(() -> processingLock.execute(7L, () -> {
                assertThat(processingLock.execute(7L, () -> "内部切分")).isEqualTo("内部切分");
                entered.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("等待超时");
                }
                throw new IllegalStateException("处理失败");
            })).isInstanceOf(IllegalStateException.class).hasMessage("处理失败"));

            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> processingLock.execute(7L, () -> "重复处理"))
                    .isInstanceOf(DocumentProcessingLock.DocumentBusyException.class);
            assertThat(processingLock.execute(8L, () -> "其他文档正常处理")).isEqualTo("其他文档正常处理");
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
            assertThat(processingLock.execute(7L, () -> "重新处理")).isEqualTo("重新处理");
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void ingestionAndManualSplitCannotProcessSameDocumentTogether() throws Exception {
        MineruClient mineruClient = mock(MineruClient.class);
        MinioService minioService = mock(MinioService.class);
        VisionService visionService = mock(VisionService.class);
        SegmentEmbeddingService embeddingService = mock(SegmentEmbeddingService.class);
        KnowledgeDocumentMapper documentMapper = mock(KnowledgeDocumentMapper.class);
        KnowledgeDocumentVersionMapper versionMapper = mock(KnowledgeDocumentVersionMapper.class);
        KnowledgeSegmentMapper segmentMapper = mock(KnowledgeSegmentMapper.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        DocumentSplitService splitService = new DocumentSplitService(minioService, embeddingService, events,
                documentMapper, versionMapper, segmentMapper, processingLock, transactionTemplate);
        DocumentIngestService ingestService = new DocumentIngestService(mineruClient, minioService, visionService,
                events, processingLock, documentMapper, versionMapper);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new DocumentController(minioService,
                mock(com.knowstack.engine.document.service.impl.ExcelProcessServiceImpl.class), events,
                splitService, documentMapper, versionMapper, segmentMapper)).build();

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        KnowledgeDocument document = new KnowledgeDocument();
        document.setStatus(DocumentStatus.UPLOADED);
        document.setCurrentVersionId(1L);
        document.setDocTitle("a.pdf");
        when(documentMapper.selectById(7L)).thenReturn(document);
        KnowledgeDocumentVersion version = new KnowledgeDocumentVersion();
        version.setDocUrl("source-url");
        when(versionMapper.selectById(1L)).thenReturn(version);
        when(minioService.objectName("source-url")).thenReturn("source");
        when(minioService.downloadBytes("source")).thenReturn(new byte[]{1});
        when(mineruClient.parseToZip(eq("a.pdf"), any(byte[].class))).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("等待超时");
            }
            throw new IllegalStateException("MinerU 暂不可用");
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> first = executor.submit(() -> ingestService.convert(7L));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> ingestService.convert(7L))
                    .isInstanceOf(DocumentProcessingLock.DocumentBusyException.class);
            mvc.perform(post("/api/documents/split/7"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("文档正在处理: 7"));
            verify(mineruClient, times(1)).parseToZip(eq("a.pdf"), any(byte[].class));
            verifyNoInteractions(transactionTemplate);
            release.countDown();
            assertThatThrownBy(() -> first.get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(IllegalStateException.class);
            assertThat(processingLock.execute(7L, () -> "可以重试")).isEqualTo("可以重试");
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void recoveryAndListenerDoNotEmbedTheSameDocumentConcurrently() throws Exception {
        KnowledgeDocument document = new KnowledgeDocument();
        document.setDocId(7L);
        document.setStatus(DocumentStatus.CHUNKED);
        document.setCurrentVersionId(1L);
        document.setDocTitle("example.pdf");
        KnowledgeDocumentMapper documents = mock(KnowledgeDocumentMapper.class);
        when(documents.selectById(7L)).thenReturn(document);
        when(documents.selectList(any())).thenReturn(java.util.List.of(document));
        SegmentEmbeddingService embedding = mock(SegmentEmbeddingService.class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("等待超时");
            }
            document.setStatus(DocumentStatus.VECTOR_STORED);
            return null;
        }).when(embedding).embed(eq(7L), eq(1L), any());
        DocumentEventListener listener = new DocumentEventListener(mock(DocumentIngestService.class),
                mock(DocumentSplitService.class), embedding, processingLock, documents);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        doAnswer(invocation -> {
            listener.onChunked(invocation.getArgument(0));
            return null;
        }).when(events).publishEvent(any(DocumentEvents.Chunked.class));
        DocumentStageRecoveryJob job = new DocumentStageRecoveryJob(documents, events);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> first = executor.submit(() -> listener.onChunked(new DocumentEvents.Chunked(7L)));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            job.recover(); // 定时扫描读到旧 CHUNKED 状态，也只能发布事件，不能并发向量化
            verify(embedding, times(1)).embed(eq(7L), eq(1L), any());
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
            job.recover(); // 锁释放后必须重读状态，不得重复向量化
            verify(embedding, times(1)).embed(eq(7L), eq(1L), any());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void splitCommitsBeforeReleasingDocumentLock() {
        RedissonClient client = lockClient();
        DocumentProcessingLock lock = new DocumentProcessingLock(client);
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        doAnswer(invocation -> {
            assertThat(client.getLock("document:processing:7").isHeldByCurrentThread()).isTrue();
            return null;
        }).when(txManager).commit(any());
        KnowledgeDocumentMapper documentMapper = mock(KnowledgeDocumentMapper.class);
        KnowledgeSegmentMapper segmentMapper = mock(KnowledgeSegmentMapper.class);
        KnowledgeDocument document = new KnowledgeDocument();
        document.setStatus(DocumentStatus.CHUNKED);
        when(documentMapper.selectById(7L)).thenReturn(document);
        when(segmentMapper.selectCount(any())).thenReturn(3L);
        DocumentSplitService splitService = new DocumentSplitService(mock(MinioService.class),
                mock(SegmentEmbeddingService.class), mock(ApplicationEventPublisher.class), documentMapper,
                mock(KnowledgeDocumentVersionMapper.class), segmentMapper, lock, new TransactionTemplate(txManager));

        assertThat(splitService.split(7L)).isEqualTo(3);
        verify(txManager).commit(any());
        assertThat(client.getLock("document:processing:7").isHeldByCurrentThread()).isFalse();
    }

    private static RedissonClient lockClient() {
        RedissonClient client = mock(RedissonClient.class);
        ConcurrentHashMap<String, RLock> locks = new ConcurrentHashMap<>();
        when(client.getLock(any(String.class))).thenAnswer(invocation -> locks.computeIfAbsent(
                invocation.getArgument(0), key -> {
                    ReentrantLock actual = new ReentrantLock();
                    RLock lock = mock(RLock.class);
                    when(lock.tryLock()).thenAnswer(ignored -> actual.tryLock());
                    when(lock.isHeldByCurrentThread()).thenAnswer(ignored -> actual.isHeldByCurrentThread());
                    doAnswer(ignored -> {
                        actual.unlock();
                        return null;
                    }).when(lock).unlock();
                    return lock;
                }));
        return client;
    }
}
