package com.knowstack.engine.document.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.knowstack.engine.document.constant.DocumentStatus;
import com.knowstack.engine.document.constant.FileType;
import com.knowstack.engine.document.constant.KnowledgeBaseType;
import com.knowstack.engine.document.constant.SplitPreset;
import com.knowstack.engine.document.constant.SplitType;
import com.knowstack.engine.document.entity.DocumentSplitParam;
import com.knowstack.engine.document.entity.KnowledgeDocument;
import com.knowstack.engine.document.entity.KnowledgeDocumentVersion;
import com.knowstack.engine.document.entity.KnowledgeSegment;
import com.knowstack.engine.document.mapper.KnowledgeDocumentMapper;
import com.knowstack.engine.document.mapper.KnowledgeDocumentVersionMapper;
import com.knowstack.engine.document.mapper.KnowledgeSegmentMapper;
import com.knowstack.engine.document.service.DocumentIngestService;
import com.knowstack.engine.document.event.DocumentEvents;
import com.knowstack.engine.document.service.DocumentProcessingLock;
import com.knowstack.engine.document.service.DocumentSplitService;
import com.knowstack.engine.document.service.MinioService;
import com.knowstack.engine.document.util.FileTypeUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 文档接口：上传（异步构建索引）、手动切分（幂等）、分段预览
 */
@Slf4j
@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final MinioService minioService;
    private final ApplicationEventPublisher events;
    private final DocumentSplitService splitService;
    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeDocumentVersionMapper versionMapper;
    private final KnowledgeSegmentMapper segmentMapper;

    /**
     * 文件上传接口
     *
     * @param file         上传的 PDF 文件
     * @param uploadUser   上传用户
     * @param accessibleBy 可见范围（可选）
     * @return 保存后的文档记录（异步流水线随后推进状态）
     */
    @PostMapping("/upload")
    @Transactional
    public KnowledgeDocument upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("uploadUser") String uploadUser,
            @RequestParam(value = "accessibleBy", required = false) String accessibleBy,
            @RequestParam(value = "docType", required = false) String docType) throws IOException {
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
        // 按文档类型预设设置切分参数（chunkSize/overlap），未传则用默认预设
        SplitPreset preset = docType != null && !docType.isBlank()
                ? SplitPreset.valueOf(docType.trim().toUpperCase(Locale.ROOT))
                : SplitPreset.DEFAULT;
        document.setSplitParam(preset.toParam());
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

        // 事务提交后触发上传事件；后续阶段由事件监听器接力
        events.publishEvent(new DocumentEvents.Uploaded(document.getDocId()));
        log.info("发布文档 UPLOADED 事件 docId={}, versionId={}", document.getDocId(), version.getVersionId());
        log.info("文档上传成功 docId={}, title={}, uploadUser={}", document.getDocId(), fileName, uploadUser);
        return document;
    }

    /**
     * 手动切分（幂等）：不传参数且已切分时直接返回现有子分段数量；
     * 传 docType/chunkSize/overlap 则更新切分参数并强制重切——
     * 已向量化的文档会先清理旧向量，分段提交后再通过事件重新嵌入
     */
    @PostMapping("/split/{documentId}")
    public Integer split(@PathVariable Long documentId,
                         @RequestParam(value = "docType", required = false) String docType,
                         @RequestParam(value = "chunkSize", required = false) Integer chunkSize,
                         @RequestParam(value = "overlap", required = false) Integer overlap) {
        if (docType == null && chunkSize == null) {
            return splitService.split(documentId);
        }
        SplitPreset preset = docType != null
                ? SplitPreset.valueOf(docType.trim().toUpperCase(Locale.ROOT))
                : SplitPreset.DEFAULT;
        DocumentSplitParam param = new DocumentSplitParam(SplitType.SMART.name(),
                chunkSize != null ? chunkSize : preset.getChunkSize(),
                overlap != null ? overlap : preset.getOverlap(),
                null, null, null);
        return splitService.split(documentId, param);
    }

    /**
     * 分段预览：按顺序返回文档的所有分段（父分段 skipEmbedding=1，子分段=0）
     */
    @GetMapping("/{documentId}/segments")
    public List<KnowledgeSegment> segments(@PathVariable Long documentId) {
        return segmentMapper.selectList(new LambdaQueryWrapper<KnowledgeSegment>()
                .eq(KnowledgeSegment::getDocumentId, documentId)
                .orderByAsc(KnowledgeSegment::getChunkOrder));
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

    @ExceptionHandler(DocumentProcessingLock.DocumentBusyException.class)
    public ResponseEntity<Map<String, String>> documentBusy(DocumentProcessingLock.DocumentBusyException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
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
