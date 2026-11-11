package com.knowstack.engine.document.service;

import com.alibaba.excel.EasyExcel;
import com.knowstack.engine.document.constant.DocumentStatus;
import com.knowstack.engine.document.constant.KnowledgeBaseType;
import com.knowstack.engine.document.controller.DocumentController;
import com.knowstack.engine.document.entity.KnowledgeDocument;
import com.knowstack.engine.document.entity.KnowledgeDocumentVersion;
import com.knowstack.engine.document.entity.TableMeta;
import com.knowstack.engine.document.mapper.KnowledgeDocumentMapper;
import com.knowstack.engine.document.mapper.KnowledgeDocumentVersionMapper;
import com.knowstack.engine.document.mapper.KnowledgeSegmentMapper;
import com.knowstack.engine.document.mapper.TableMetaMapper;
import com.knowstack.engine.document.service.impl.ExcelProcessServiceImpl;
import com.knowstack.engine.document.event.DocumentEvents;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.sql.PreparedStatement;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ExcelImportTest {
    private byte[] workbook() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        EasyExcel.write(output).head(List.of(List.of("Name"), List.of("Name"), List.of("金额")))
                .sheet().doWrite(List.of(List.of("O'Brien", "A\\B", "12")));
        return output.toByteArray();
    }

    @Test
    void importsWorkbookIntoDocumentOwnedTableWithBoundValues() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TableMetaMapper metaMapper = mock(TableMetaMapper.class);
        when(metaMapper.insert(any(TableMeta.class))).thenReturn(1);
        KnowledgeDocument document = new KnowledgeDocument();
        document.setDocId(42L);
        document.setCurrentVersionId(7L);
        document.setDocTitle("报价.xlsx");
        new ExcelProcessServiceImpl(metaMapper, jdbc).processDocument(document, new ByteArrayInputStream(workbook()));

        ArgumentCaptor<TableMeta> meta = ArgumentCaptor.forClass(TableMeta.class);
        verify(metaMapper).insert(meta.capture());
        assertThat(meta.getValue().getTableName()).isEqualTo("custom_data_query_42");
        assertThat(meta.getValue().getColumnsInfo()).contains("\"columnName\":\"col_1\"", "\"columnName\":\"col_2\"", "\"columnName\":\"col_3\"");
        verify(jdbc).execute(org.mockito.ArgumentMatchers.contains("CREATE TABLE `custom_data_query_42`"));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<BatchPreparedStatementSetter> setter = ArgumentCaptor.forClass(BatchPreparedStatementSetter.class);
        verify(jdbc).batchUpdate(sql.capture(), setter.capture());
        assertThat(sql.getValue()).contains("`col_1`, `col_2`, `col_3`").contains("VALUES (?, ?, ?)")
                .doesNotContain("O'Brien");
        PreparedStatement ps = mock(PreparedStatement.class);
        setter.getValue().setValues(ps, 0);
        verify(ps).setString(1, "O'Brien");
        verify(ps).setString(2, "A\\B");
        verify(ps).setString(3, "12");
    }

    @Test
    void importsCsvWithQuotedCommasNewlinesEscapedQuotesAndBom() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TableMetaMapper mapper = mock(TableMetaMapper.class);
        when(mapper.insert(any(TableMeta.class))).thenReturn(1);
        KnowledgeDocument document = new KnowledgeDocument();
        document.setDocId(52L);
        document.setCurrentVersionId(9L);
        document.setDocTitle("报价.csv");
        byte[] csv = "\uFEFF名称,备注,id,名称,含`符\r\n张三,\"第一行,\r\n第二行\"\"引用\"\"\",42,重复,安全\r\n".getBytes(StandardCharsets.UTF_8);
        new ExcelProcessServiceImpl(mapper, jdbc).processDocument(document, new ByteArrayInputStream(csv));
        ArgumentCaptor<TableMeta> meta = ArgumentCaptor.forClass(TableMeta.class);
        verify(mapper).insert(meta.capture());
        assertThat(meta.getValue().getColumnsInfo()).contains("\"columnName\":\"col_1\"", "\"columnName\":\"col_2\"", "\"columnName\":\"col_3\"", "\"columnName\":\"col_5\"");
        ArgumentCaptor<BatchPreparedStatementSetter> batch = ArgumentCaptor.forClass(BatchPreparedStatementSetter.class);
        verify(jdbc).batchUpdate(org.mockito.ArgumentMatchers.contains("`col_1`, `col_2`, `col_3`, `col_4`, `col_5`"), batch.capture());
        PreparedStatement ps = mock(PreparedStatement.class);
        batch.getValue().setValues(ps, 0);
        verify(ps).setString(1, "张三");
        verify(ps).setString(2, "第一行,\r\n第二行\"引用\"");
    }

    @Test
    void removesNewTableWhenMetadataCannotBeSaved() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TableMetaMapper mapper = mock(TableMetaMapper.class);
        KnowledgeDocument document = new KnowledgeDocument();
        document.setDocId(51L);
        document.setCurrentVersionId(8L);
        document.setDocTitle("报价.xlsx");
        assertThatThrownBy(() -> new ExcelProcessServiceImpl(mapper, jdbc)
                .processDocument(document, new ByteArrayInputStream(workbook())))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("表元数据保存失败");
        verify(jdbc).execute("DROP TABLE `custom_data_query_51`");
    }

    @Test
    void excelUploadDoesNotPublishPdfPipelineEvent() throws Exception {
        MinioService minio = mock(MinioService.class);
        ExcelProcessServiceImpl processor = mock(ExcelProcessServiceImpl.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        KnowledgeDocumentMapper documents = mock(KnowledgeDocumentMapper.class);
        KnowledgeDocumentVersionMapper versions = mock(KnowledgeDocumentVersionMapper.class);
        when(documents.insert(any(KnowledgeDocument.class))).thenAnswer(invocation -> {
            KnowledgeDocument doc = invocation.getArgument(0);
            doc.setDocId(42L);
            return 1;
        });
        when(versions.insert(any(KnowledgeDocumentVersion.class))).thenAnswer(invocation -> {
            KnowledgeDocumentVersion version = invocation.getArgument(0);
            version.setVersionId(7L);
            return 1;
        });
        var mvc = MockMvcBuilders.standaloneSetup(new DocumentController(
                new DocumentUploadService(minio, processor, events, documents, versions),
                mock(DocumentSplitService.class), documents, mock(KnowledgeSegmentMapper.class),
                mock(DocumentHistoryService.class))).build();
        mvc.perform(multipart("/api/documents/upload")
                        .file(new MockMultipartFile("file", "报价.xlsm", "application/vnd.ms-excel.sheet.macroEnabled.12", workbook()))
                        .param("uploadUser", "alice"))
                .andExpect(status().isOk());
        mvc.perform(multipart("/api/documents/upload")
                        .file(new MockMultipartFile("file", "报价.csv", "text/csv",
                                "名称,金额\r\n张三,12\r\n".getBytes(StandardCharsets.UTF_8)))
                        .param("uploadUser", "alice"))
                .andExpect(status().isOk());
        ArgumentCaptor<KnowledgeDocument> doc = ArgumentCaptor.forClass(KnowledgeDocument.class);
        verify(documents, atLeastOnce()).updateById(doc.capture());
        assertThat(doc.getValue().getStatus()).isEqualTo(DocumentStatus.STORED);
        assertThat(doc.getValue().getKnowledgeBaseType()).isEqualTo(KnowledgeBaseType.DATA_QUERY);
        verify(processor, times(2)).processDocument(any(KnowledgeDocument.class), any());
        verifyNoInteractions(events);
    }

    @Test
    void wordUploadsEnterDocumentPipelineAndConvertToMarkdown() throws Exception {
        MinioService minio = mock(MinioService.class);
        ExcelProcessServiceImpl processor = mock(ExcelProcessServiceImpl.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        KnowledgeDocumentMapper documents = mock(KnowledgeDocumentMapper.class);
        KnowledgeDocumentVersionMapper versions = mock(KnowledgeDocumentVersionMapper.class);
        MineruClient mineru = mock(MineruClient.class);
        DocumentProcessingLock lock = mock(DocumentProcessingLock.class);
        when(documents.insert(any(KnowledgeDocument.class))).thenAnswer(invocation -> {
            invocation.<KnowledgeDocument>getArgument(0).setDocId(42L);
            return 1;
        });
        when(versions.insert(any(KnowledgeDocumentVersion.class))).thenAnswer(invocation -> {
            invocation.<KnowledgeDocumentVersion>getArgument(0).setVersionId(7L);
            return 1;
        });
        when(minio.upload(any(), any(), any())).thenReturn("source-url");
        var mvc = MockMvcBuilders.standaloneSetup(new DocumentController(
                new DocumentUploadService(minio, processor, events, documents, versions),
                mock(DocumentSplitService.class), documents, mock(KnowledgeSegmentMapper.class),
                mock(DocumentHistoryService.class))).build();
        for (String extension : List.of("doc", "docx")) {
            mvc.perform(multipart("/api/documents/upload")
                            .file(new MockMultipartFile("file", "report." + extension, "application/octet-stream",
                                    new byte[]{1, 2, 3}))
                            .param("uploadUser", "alice"))
                    .andExpect(status().isOk());
        }
        verify(events, times(2)).publishEvent(any(DocumentEvents.Uploaded.class));
        verifyNoInteractions(processor);
        ArgumentCaptor<KnowledgeDocumentVersion> saved = ArgumentCaptor.forClass(KnowledgeDocumentVersion.class);
        verify(versions, times(2)).insert(saved.capture());
        assertThat(saved.getAllValues()).allSatisfy(version ->
                assertThat(version.getStatus()).isEqualTo(DocumentStatus.UPLOADED));
        verify(minio).upload(org.mockito.ArgumentMatchers.endsWith("report.doc"), any(), eq("application/msword"));
        verify(minio).upload(org.mockito.ArgumentMatchers.endsWith("report.docx"), any(),
                eq("application/vnd.openxmlformats-officedocument.wordprocessingml.document"));

        KnowledgeDocument document = new KnowledgeDocument();
        document.setDocId(42L);
        document.setCurrentVersionId(7L);
        document.setDocTitle("report.docx");
        document.setStatus(DocumentStatus.UPLOADED);
        KnowledgeDocumentVersion version = new KnowledgeDocumentVersion();
        version.setVersionId(7L);
        version.setDocUrl("source-url");
        when(documents.selectById(42L)).thenReturn(document);
        when(versions.selectById(7L)).thenReturn(version);
        when(minio.objectName("source-url")).thenReturn("source");
        when(minio.downloadBytes("source")).thenReturn(new byte[]{1, 2, 3});
        when(lock.execute(eq(42L), any())).thenAnswer(invocation -> invocation.<Callable<Boolean>>getArgument(1).call());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("report.md"));
            zip.write("# Word heading\nBody".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        when(mineru.parseToZip(eq("report.docx"), any())).thenReturn(output.toByteArray());
        new DocumentIngestService(mineru, minio, mock(VisionService.class), events, lock, documents, versions)
                .convert(42L);
        verify(minio).upload(eq("42/7/report.md"), eq("# Word heading\nBody".getBytes(StandardCharsets.UTF_8)),
                eq("text/markdown"));
        verify(events).publishEvent(new DocumentEvents.Converted(42L));
        verify(versions, atLeastOnce()).updateById(org.mockito.ArgumentMatchers.<KnowledgeDocumentVersion>argThat(v ->
                v.getStatus() == DocumentStatus.CONVERTED && "source-url".equals(v.getConvertedDocUrl())));
    }

    @Test
    void markdownUploadDownloadsReferencedUrlImagesAndRewritesToMinio() throws Exception {
        byte[] png = new byte[]{1, 'P', 'N', 'G'};
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(0), 0);
        server.createContext("/img/shot.png", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, png.length);
            exchange.getResponseBody().write(png);
            exchange.close();
        });
        server.start();
        int port = server.getAddress().getPort();
        try {
            MinioService minio = mock(MinioService.class);
            ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
            KnowledgeDocumentMapper documents = mock(KnowledgeDocumentMapper.class);
            KnowledgeDocumentVersionMapper versions = mock(KnowledgeDocumentVersionMapper.class);
            DocumentProcessingLock lock = mock(DocumentProcessingLock.class);
            VisionService vision = mock(VisionService.class);
            when(documents.insert(any(KnowledgeDocument.class))).thenAnswer(invocation -> {
                invocation.<KnowledgeDocument>getArgument(0).setDocId(9L);
                return 1;
            });
            when(versions.insert(any(KnowledgeDocumentVersion.class))).thenAnswer(invocation -> {
                invocation.<KnowledgeDocumentVersion>getArgument(0).setVersionId(3L);
                return 1;
            });
            String imageUrl = "http://127.0.0.1:" + port + "/img/shot.png";
            String markdown = "# 标题\n\n![截图](" + imageUrl + ") 与失效链接 ![坏](http://127.0.0.1:1/dead.png)\n";
            var mvc = MockMvcBuilders.standaloneSetup(new DocumentController(
                    new DocumentUploadService(minio, mock(ExcelProcessServiceImpl.class), events, documents, versions),
                    mock(DocumentSplitService.class), documents, mock(KnowledgeSegmentMapper.class),
                    mock(DocumentHistoryService.class))).build();
            mvc.perform(multipart("/api/documents/upload")
                            .file(new MockMultipartFile("file", "notes.md", "text/markdown",
                                    markdown.getBytes(StandardCharsets.UTF_8)))
                            .param("uploadUser", "alice"))
                    .andExpect(status().isOk());
            verify(events, times(1)).publishEvent(any(DocumentEvents.Uploaded.class));

            KnowledgeDocument document = new KnowledgeDocument();
            document.setDocId(9L);
            document.setCurrentVersionId(3L);
            document.setDocTitle("notes.md");
            document.setStatus(DocumentStatus.UPLOADED);
            KnowledgeDocumentVersion version = new KnowledgeDocumentVersion();
            version.setVersionId(3L);
            version.setDocUrl("http://minio/knowstack/abc123/source/notes.md");
            when(documents.selectById(9L)).thenReturn(document);
            when(versions.selectById(3L)).thenReturn(version);
            when(minio.objectName("http://minio/knowstack/abc123/source/notes.md"))
                    .thenReturn("abc123/source/notes.md");
            when(minio.downloadBytes("abc123/source/notes.md")).thenReturn(markdown.getBytes(StandardCharsets.UTF_8));
            when(minio.upload(eq("9/3/images/shot.png"), eq(png), eq("image/png"))).thenReturn("image-url");
            when(minio.upload(eq("9/3/notes.md"), any(byte[].class), eq("text/markdown"))).thenReturn("converted-url");
            when(vision.describe(png, "image/png")).thenReturn("架构图：MinIO 与 MinerU");
            when(lock.execute(eq(9L), any())).thenAnswer(invocation -> invocation.<Callable<Boolean>>getArgument(1).call());
            new DocumentIngestService(mock(MineruClient.class), minio, vision, events, lock, documents, versions)
                    .convert(9L);
            ArgumentCaptor<byte[]> saved = ArgumentCaptor.forClass(byte[].class);
            verify(minio).upload(eq("9/3/notes.md"), saved.capture(), eq("text/markdown"));
            assertThat(new String(saved.getValue(), StandardCharsets.UTF_8))
                    .contains("![架构图：MinIO 与 MinerU](image-url)")
                    .contains("![坏](http://127.0.0.1:1/dead.png)")
                    .doesNotContain(imageUrl);
            verify(events).publishEvent(new DocumentEvents.Converted(9L));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void txtUploadEntersPipelineWithLengthSplitting() throws Exception {
        MinioService minio = mock(MinioService.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        KnowledgeDocumentMapper documents = mock(KnowledgeDocumentMapper.class);
        KnowledgeDocumentVersionMapper versions = mock(KnowledgeDocumentVersionMapper.class);
        DocumentProcessingLock lock = mock(DocumentProcessingLock.class);
        when(documents.insert(any(KnowledgeDocument.class))).thenAnswer(invocation -> {
            invocation.<KnowledgeDocument>getArgument(0).setDocId(11L);
            return 1;
        });
        when(versions.insert(any(KnowledgeDocumentVersion.class))).thenAnswer(invocation -> {
            invocation.<KnowledgeDocumentVersion>getArgument(0).setVersionId(5L);
            return 1;
        });
        var mvc = MockMvcBuilders.standaloneSetup(new DocumentController(
                new DocumentUploadService(minio, mock(ExcelProcessServiceImpl.class), events, documents, versions),
                mock(DocumentSplitService.class), documents, mock(KnowledgeSegmentMapper.class),
                mock(DocumentHistoryService.class))).build();
        byte[] text = "纯文本内容，无标题结构。".getBytes(StandardCharsets.UTF_8);
        mvc.perform(multipart("/api/documents/upload")
                        .file(new MockMultipartFile("file", "notes.txt", "text/plain", text))
                        .param("uploadUser", "alice"))
                .andExpect(status().isOk());
        verify(minio).upload(org.mockito.ArgumentMatchers.endsWith("/source/notes.txt"), eq(text), eq("text/plain"));
        verify(events, times(1)).publishEvent(any(DocumentEvents.Uploaded.class));
        ArgumentCaptor<KnowledgeDocument> doc = ArgumentCaptor.forClass(KnowledgeDocument.class);
        verify(documents).insert(doc.capture());
        assertThat(doc.getValue().getSplitParam().splitType()).isEqualTo("LENGTH");
        assertThat(doc.getValue().getSplitParam().chunkSize()).isEqualTo(384);

        KnowledgeDocument document = doc.getValue();
        document.setDocId(11L);
        document.setStatus(DocumentStatus.UPLOADED);
        KnowledgeDocumentVersion version = new KnowledgeDocumentVersion();
        version.setVersionId(5L);
        version.setDocUrl("http://minio/knowstack/k/source/notes.txt");
        when(documents.selectById(11L)).thenReturn(document);
        when(versions.selectById(5L)).thenReturn(version);
        when(minio.objectName("http://minio/knowstack/k/source/notes.txt")).thenReturn("k/source/notes.txt");
        when(minio.downloadBytes("k/source/notes.txt")).thenReturn(text);
        when(minio.upload(eq("11/5/notes.md"), any(byte[].class), eq("text/markdown"))).thenReturn("converted-url");
        when(lock.execute(eq(11L), any())).thenAnswer(invocation -> invocation.<Callable<Boolean>>getArgument(1).call());
        MineruClient mineru = mock(MineruClient.class);
        new DocumentIngestService(mineru, minio, mock(VisionService.class), events, lock, documents, versions)
                .convert(11L);
        verifyNoInteractions(mineru);
        ArgumentCaptor<byte[]> saved = ArgumentCaptor.forClass(byte[].class);
        verify(minio).upload(eq("11/5/notes.md"), saved.capture(), eq("text/markdown"));
        assertThat(new String(saved.getValue(), StandardCharsets.UTF_8)).isEqualTo("纯文本内容，无标题结构。");
        verify(events).publishEvent(new DocumentEvents.Converted(11L));
    }

    @Test
    void splitTypeIsSelectableFromFrontendAndValidated() throws Exception {
        MinioService minio = mock(MinioService.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        KnowledgeDocumentMapper documents = mock(KnowledgeDocumentMapper.class);
        KnowledgeDocumentVersionMapper versions = mock(KnowledgeDocumentVersionMapper.class);
        when(documents.insert(any(KnowledgeDocument.class))).thenAnswer(invocation -> {
            invocation.<KnowledgeDocument>getArgument(0).setDocId(12L);
            return 1;
        });
        when(versions.insert(any(KnowledgeDocumentVersion.class))).thenAnswer(invocation -> {
            invocation.<KnowledgeDocumentVersion>getArgument(0).setVersionId(6L);
            return 1;
        });
        var mvc = MockMvcBuilders.standaloneSetup(new DocumentController(
                new DocumentUploadService(minio, mock(ExcelProcessServiceImpl.class), events, documents, versions),
                mock(DocumentSplitService.class), documents, mock(KnowledgeSegmentMapper.class),
                mock(DocumentHistoryService.class))).build();
        mvc.perform(multipart("/api/documents/upload")
                        .file(new MockMultipartFile("file", "doc.md", "text/markdown", "# h\nbody".getBytes()))
                        .param("uploadUser", "alice")
                        .param("splitType", "TITLE")
                        .param("titleLevel", "2"))
                .andExpect(status().isOk());
        ArgumentCaptor<KnowledgeDocument> doc = ArgumentCaptor.forClass(KnowledgeDocument.class);
        verify(documents).insert(doc.capture());
        assertThat(doc.getValue().getSplitParam().splitType()).isEqualTo("TITLE");
        assertThat(doc.getValue().getSplitParam().titleLevel()).isEqualTo(2);
        assertThat(doc.getValue().getSplitParam().chunkSize()).isEqualTo(1000);

        mvc.perform(multipart("/api/documents/upload")
                        .file(new MockMultipartFile("file", "doc.md", "text/markdown", "# h".getBytes()))
                        .param("uploadUser", "alice")
                        .param("splitType", "NOT_A_TYPE"))
                .andExpect(status().isBadRequest());
    }
}
