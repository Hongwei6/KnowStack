package com.knowstack.engine.document.service;

import com.knowstack.engine.document.constant.DocumentStatus;
import com.knowstack.engine.document.constant.FileType;
import com.knowstack.engine.document.constant.KnowledgeBaseType;
import com.knowstack.engine.document.constant.SplitPreset;
import com.knowstack.engine.document.entity.DocumentSplitParam;
import com.knowstack.engine.document.entity.KnowledgeDocument;
import com.knowstack.engine.document.entity.KnowledgeDocumentVersion;
import com.knowstack.engine.document.entity.SplitOptions;
import com.knowstack.engine.document.event.DocumentEvents;
import com.knowstack.engine.document.mapper.KnowledgeDocumentMapper;
import com.knowstack.engine.document.mapper.KnowledgeDocumentVersionMapper;
import com.knowstack.engine.document.service.impl.ExcelProcessServiceImpl;
import com.knowstack.engine.document.util.FileTypeUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.UUID;

/**
 * 文档上传：原文件存 MinIO，文档/版本记录写 MySQL（一个事务），
 * 表格走同步导入，其余发布 Uploaded 事件交由异步流水线处理。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentUploadService {

    private final MinioService minioService;
    private final ExcelProcessServiceImpl excelProcessService;
    private final ApplicationEventPublisher events;
    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeDocumentVersionMapper versionMapper;

    /** 统一上传入口：按文件类型路由到表格导入或文档解析流水线。 */
    @Transactional
    public KnowledgeDocument upload(MultipartFile file, String uploadUser, String accessibleBy,
                                    SplitOptions options) throws IOException {
        String fileName = sanitize(file.getOriginalFilename());
        FileType fileType = FileTypeUtil.getFileType(fileName, file);
        if (fileType == FileType.CSV || (fileType == FileType.EXCEL
                && fileName.toLowerCase(Locale.ROOT).matches(".*\\.(xls|xlsx|xlsm|xlt|xltx|xltm)"))) {
            return uploadExcel(file, uploadUser, accessibleBy, fileName);
        }
        if (fileType != FileType.PDF && fileType != FileType.DOC && fileType != FileType.MARKDOWN
                && fileType != FileType.TXT) {
            throw new IllegalArgumentException("仅支持 PDF、Word、Markdown、TXT、CSV 或 Excel 表格文件: " + fileName);
        }
        String lowerName = fileName.toLowerCase(Locale.ROOT);
        if (fileType == FileType.DOC && !lowerName.matches(".*\\.(doc|docx)")) {
            throw new IllegalArgumentException("Word 文件须为 .doc 或 .docx: " + fileName);
        }
        if (fileType == FileType.MARKDOWN && !lowerName.endsWith(".md")) {
            throw new IllegalArgumentException("Markdown 文件须为 .md: " + fileName);
        }
        if (fileType == FileType.TXT && !lowerName.endsWith(".txt")) {
            throw new IllegalArgumentException("文本文件须为 .txt: " + fileName);
        }
        byte[] content = file.getBytes();

        // 1. 原始文件上传 MinIO
        String storageKey = UUID.randomUUID().toString().replace("-", "");
        String contentType = fileType == FileType.PDF ? "application/pdf"
                : fileType == FileType.MARKDOWN ? "text/markdown"
                : fileType == FileType.TXT ? "text/plain"
                : lowerName.endsWith(".docx")
                ? "application/vnd.openxmlformats-officedocument.wordprocessingml.document" : "application/msword";
        String sourceUrl = minioService.upload(storageKey + "/source/" + fileName, content, contentType);

        // 2. 文档记录；切分方式可由前端指定，未指定时 TXT 无标题结构默认 LENGTH，其余默认 SMART（标题+父子分段）
        KnowledgeDocument document = new KnowledgeDocument();
        document.setDocTitle(fileName);
        document.setStatus(DocumentStatus.UPLOADED);
        document.setAccessibleBy(accessibleBy);
        document.setKnowledgeBaseType(KnowledgeBaseType.DOCUMENT_SEARCH);
        SplitPreset preset = options.docType() != null && !options.docType().isBlank()
                ? SplitPreset.valueOf(options.docType().trim().toUpperCase(Locale.ROOT))
                : fileType == FileType.TXT ? SplitPreset.PLAIN_TEXT : SplitPreset.DEFAULT;
        document.setSplitParam(new DocumentSplitParam(DocumentSplitService.resolveSplitType(options.splitType(),
                fileType == FileType.TXT && (options.docType() == null || options.docType().isBlank())).name(),
                preset.getChunkSize(), preset.getOverlap(), options.titleLevel(), options.separator(), options.regex()));
        documentMapper.insert(document);

        // 3. 版本记录（doc_url / converted_doc_url 保存在版本表）
        KnowledgeDocumentVersion version = new KnowledgeDocumentVersion();
        version.setDocId(document.getDocId());
        version.setVersion("1.0.0");
        version.setDocUrl(sourceUrl);
        version.setUploadUser(uploadUser);
        version.setStatus(DocumentStatus.UPLOADED);
        versionMapper.insert(version);

        document.setCurrentVersionId(version.getVersionId());
        documentMapper.updateById(document);

        // 事务提交后触发上传事件；后续阶段由事件监听器接力
        events.publishEvent(new DocumentEvents.Uploaded(document.getDocId()));
        log.info("发布文档 UPLOADED 事件 docId={}, versionId={}", document.getDocId(), version.getVersionId());
        log.info("文档上传成功 docId={}, title={}, uploadUser={}", document.getDocId(), fileName, uploadUser);
        return document;
    }

    /** Excel/CSV 同步导入 MySQL，不发布文档解析流水线事件。 */
    private KnowledgeDocument uploadExcel(MultipartFile file, String uploadUser,
                                          String accessibleBy, String fileName) throws IOException {
        if (uploadUser.isBlank()) {
            throw new IllegalArgumentException("上传用户不能为空");
        }
        String lowerName = fileName.toLowerCase(Locale.ROOT);
        String contentType = lowerName.endsWith(".csv") ? "text/csv"
                : lowerName.matches(".*\\.(xlsx|xlsm|xltx|xltm)")
                ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" : "application/vnd.ms-excel";
        String url = minioService.upload(UUID.randomUUID().toString().replace("-", "")
                + "/source/" + fileName, file.getBytes(), contentType);

        KnowledgeDocument document = new KnowledgeDocument();
        document.setDocTitle(fileName);
        document.setStatus(DocumentStatus.INIT);
        document.setAccessibleBy(accessibleBy);
        document.setKnowledgeBaseType(KnowledgeBaseType.DATA_QUERY);
        documentMapper.insert(document);

        KnowledgeDocumentVersion version = new KnowledgeDocumentVersion();
        version.setDocId(document.getDocId());
        version.setVersion("1.0.0");
        version.setDocUrl(url);
        version.setUploadUser(uploadUser);
        version.setStatus(DocumentStatus.INIT);
        versionMapper.insert(version);
        document.setCurrentVersionId(version.getVersionId());
        documentMapper.updateById(document);

        excelProcessService.processDocument(document, file.getInputStream());
        document.setStatus(DocumentStatus.STORED);
        version.setStatus(DocumentStatus.STORED);
        versionMapper.updateById(version);
        documentMapper.updateById(document);
        log.info("Excel 已入库 docId={}, versionId={}", document.getDocId(), version.getVersionId());
        return document;
    }

    private String sanitize(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("文件名不能为空");
        }
        return Paths.get(name.replace("\\", "/")).getFileName().toString().trim();
    }
}
