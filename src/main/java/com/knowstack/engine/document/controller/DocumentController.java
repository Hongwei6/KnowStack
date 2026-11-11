package com.knowstack.engine.document.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.knowstack.engine.document.entity.KnowledgeDocument;
import com.knowstack.engine.document.entity.KnowledgeSegment;
import com.knowstack.engine.document.entity.SplitOptions;
import com.knowstack.engine.document.mapper.KnowledgeDocumentMapper;
import com.knowstack.engine.document.mapper.KnowledgeSegmentMapper;
import com.knowstack.engine.document.service.DocumentHistoryService;
import com.knowstack.engine.document.service.DocumentProcessingLock;
import com.knowstack.engine.document.service.DocumentSplitService;
import com.knowstack.engine.document.service.DocumentUploadService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 文档接口：上传（异步构建索引）、手动切分（幂等）、分段预览
 */
@Slf4j
@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentUploadService uploadService;
    private final DocumentSplitService splitService;
    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeSegmentMapper segmentMapper;
    private final DocumentHistoryService historyService;

    /**
     * 文件上传接口（业务见 DocumentUploadService：类型路由、MinIO 存储、事件发布）
     *
     * @param file         上传的 PDF、Word、Markdown、TXT 或表格文件
     * @param uploadUser   上传用户
     * @param accessibleBy 可见范围（可选）
     * @param docType      切分预设（可选）
     * @param splitType    切分方式 SMART/TITLE/LENGTH/SEPARATOR/REGEX（可选）
     * @param titleLevel   标题层级（TITLE 可选）
     * @param separator    分隔符（SEPARATOR 必填）
     * @param regex        正则（REGEX 必填）
     */
    @PostMapping("/upload")
    public KnowledgeDocument upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("uploadUser") String uploadUser,
            @RequestParam(value = "accessibleBy", required = false) String accessibleBy,
            @RequestParam(value = "docType", required = false) String docType,
            @RequestParam(value = "splitType", required = false) String splitType,
            @RequestParam(value = "titleLevel", required = false) Integer titleLevel,
            @RequestParam(value = "separator", required = false) String separator,
            @RequestParam(value = "regex", required = false) String regex) throws IOException {
        return uploadService.upload(file, uploadUser, accessibleBy,
                new SplitOptions(docType, splitType, null, null, titleLevel, separator, regex));
    }

    /**
     * 手动切分（幂等）：不传参数且已切分时直接返回现有子分段数量；
     * 传任一切分参数则更新并强制重切（业务见 DocumentSplitService）
     */
    @PostMapping("/split/{documentId}")
    public Integer split(@PathVariable Long documentId,
                         @RequestParam(value = "docType", required = false) String docType,
                         @RequestParam(value = "splitType", required = false) String splitType,
                         @RequestParam(value = "chunkSize", required = false) Integer chunkSize,
                         @RequestParam(value = "overlap", required = false) Integer overlap,
                         @RequestParam(value = "titleLevel", required = false) Integer titleLevel,
                         @RequestParam(value = "separator", required = false) String separator,
                         @RequestParam(value = "regex", required = false) String regex) {
        return splitService.split(documentId,
                new SplitOptions(docType, splitType, chunkSize, overlap, titleLevel, separator, regex));
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

    @GetMapping
    public DocumentHistoryService.DocumentPage history(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return historyService.list(page, size);
    }

    @DeleteMapping("/{docId}")
    public ResponseEntity<Void> delete(@PathVariable Long docId) {
        historyService.delete(docId);
        return ResponseEntity.noContent().build();
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
}
