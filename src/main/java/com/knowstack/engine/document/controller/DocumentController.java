package com.knowstack.engine.document.controller;

import com.knowstack.engine.document.constant.DocumentStatus;
import com.knowstack.engine.document.constant.FileType;
import com.knowstack.engine.document.constant.KnowledgeBaseType;
import com.knowstack.engine.document.entity.KnowledgeDocument;
import com.knowstack.engine.document.entity.KnowledgeDocumentVersion;
import com.knowstack.engine.document.mapper.KnowledgeDocumentMapper;
import com.knowstack.engine.document.mapper.KnowledgeDocumentVersionMapper;
import com.knowstack.engine.document.service.DocumentIngestService;
import com.knowstack.engine.document.service.MinioService;
import com.knowstack.engine.document.util.FileTypeUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.Map;
import java.util.UUID;

/**
 * 文档上传接口：上传 PDF 到 MinIO，落库后异步触发索引构建流水线
 */
@Slf4j
@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final MinioService minioService;
    private final DocumentIngestService ingestService;
    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeDocumentVersionMapper versionMapper;

    /**
     * 文件上传接口
     *
     * @param file         上传的 PDF 文件
     * @param uploadUser   上传用户
     * @param accessibleBy 可见范围（可选）
     * @return 保存后的文档记录（异步流水线随后推进状态）
     */
    @PostMapping("/upload")
    public KnowledgeDocument upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("uploadUser") String uploadUser,
            @RequestParam(value = "accessibleBy", required = false) String accessibleBy) throws IOException {
        String fileName = sanitize(file.getOriginalFilename());
        if (FileTypeUtil.getFileType(fileName, file) != FileType.PDF) {
            throw new IllegalArgumentException("仅支持 PDF 文件: " + fileName);
        }
        byte[] pdf = file.getBytes();

        // 1. 原始 PDF 上传 MinIO
        String storageKey = UUID.randomUUID().toString().replace("-", "");
        String pdfUrl = minioService.upload(storageKey + "/source/" + fileName, pdf, "application/pdf");

        // 2. 文档记录
        KnowledgeDocument document = new KnowledgeDocument();
        document.setDocTitle(fileName);
        document.setStatus(DocumentStatus.UPLOADED);
        document.setAccessibleBy(accessibleBy);
        document.setKnowledgeBaseType(KnowledgeBaseType.DOCUMENT_SEARCH);
        documentMapper.insert(document);

        // 3. 版本记录（doc_url / converted_doc_url 保存在版本表）
        KnowledgeDocumentVersion version = new KnowledgeDocumentVersion();
        version.setDocId(document.getDocId());
        version.setVersion("1.0.0");
        version.setDocUrl(pdfUrl);
        version.setUploadUser(uploadUser);
        version.setStatus(DocumentStatus.UPLOADED);
        versionMapper.insert(version);

        document.setCurrentVersionId(version.getVersionId());
        documentMapper.updateById(document);

        // 4. 异步构建索引：MinerU 解析 → 分块 → embedding → 向量库
        ingestService.process(document.getDocId(), version.getVersionId(), fileName, document.getDocTitle(), pdf);
        log.info("文档上传成功 docId={}, title={}, uploadUser={}", document.getDocId(), fileName, uploadUser);
        return document;
    }

    /**
     * 查询文档状态（前端轮询展示流水线进度）
     */
    @GetMapping("/{docId}")
    public KnowledgeDocument status(@PathVariable Long docId) {
        KnowledgeDocument document = documentMapper.selectById(docId);
        if (document == null) {
            throw new IllegalArgumentException("文档不存在: " + docId);
        }
        return document;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    private String sanitize(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("文件名不能为空");
        }
        return Paths.get(name.replace("\\", "/")).getFileName().toString().trim();
    }
}
