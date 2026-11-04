package com.knowstack.engine.document.service;

import com.knowstack.engine.document.constant.DocumentStatus;
import com.knowstack.engine.document.constant.SegmentStatus;
import com.knowstack.engine.document.entity.KnowledgeDocument;
import com.knowstack.engine.document.entity.KnowledgeDocumentVersion;
import com.knowstack.engine.document.entity.KnowledgeSegment;
import com.knowstack.engine.document.mapper.KnowledgeDocumentMapper;
import com.knowstack.engine.document.mapper.KnowledgeDocumentVersionMapper;
import com.knowstack.engine.document.mapper.KnowledgeSegmentMapper;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 文档索引构建流水线（异步）：
 * MinerU 解析 PDF → 图片上传 MinIO + 视觉模型生成描述 → Markdown 上传 MinIO
 * → 分块入库 → embedding → 向量写入 ES
 *
 * 状态流转：UPLOADED → CONVERTING → CONVERTED → CHUNKED → VECTOR_STORED，
 * 失败时状态停留在当前阶段并记录日志
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentIngestService {

    /** ponytail: 固定 800/100 字符递归分块；需要按标题/分隔符分块时再接入 DocumentSplitParam */
    private static final int CHUNK_SIZE = 800;
    private static final int CHUNK_OVERLAP = 100;
    private static final Pattern IMAGE_TAG = Pattern.compile("!\\[(.*?)]\\(([^)]+)\\)");

    private final MineruClient mineruClient;
    private final MinioService minioService;
    private final VisionService visionService;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeDocumentVersionMapper versionMapper;
    private final KnowledgeSegmentMapper segmentMapper;

    @Async
    public void process(Long docId, Long versionId, String fileName, String docTitle, byte[] pdf) {
        try {
            doProcess(docId, versionId, fileName, docTitle, pdf);
        } catch (Exception e) {
            log.error("文档索引构建失败 docId={}, 当前状态={}", docId, documentMapper.selectById(docId).getStatus(), e);
        }
    }

    private void doProcess(Long docId, Long versionId, String fileName, String docTitle, byte[] pdf) throws IOException {
        String storageKey = UUID.randomUUID().toString().replace("-", "");
        updateStatus(docId, versionId, DocumentStatus.CONVERTING, null);

        // 1. MinerU 解析为 ZIP（Markdown + 图片）
        Map<String, byte[]> entries = unzip(mineruClient.parseToZip(fileName, pdf));
        String markdown = extractMarkdown(entries);
        if (markdown == null || markdown.isBlank()) {
            throw new IOException("MinerU 解析结果中没有 Markdown 内容");
        }

        // 2. 图片上传 MinIO，视觉模型生成描述，并把相对路径替换为可访问的 MinIO URL
        Map<String, String> imageUrls = new HashMap<>();
        Map<String, String> imageAlts = new HashMap<>();
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            String imageName = simpleName(entry.getKey());
            if (!isImage(imageName)) {
                continue;
            }
            String mimeType = imageMimeType(imageName);
            String url = minioService.upload(storageKey + "/images/" + imageName, entry.getValue(), mimeType);
            imageUrls.put(imageName, url);
            imageAlts.put(imageName, visionService.describe(entry.getValue(), mimeType));
        }
        markdown = rewriteImages(markdown, imageUrls, imageAlts);
        log.info("文档 docId={} 图片处理完成，共 {} 张", docId, imageUrls.size());

        // 3. Markdown 上传 MinIO，状态推进 CONVERTED
        String mdName = fileName.replaceAll("(?i)\\.pdf$", "") + ".md";
        String mdUrl = minioService.upload(storageKey + "/" + mdName, markdown.getBytes(StandardCharsets.UTF_8), "text/markdown");
        updateStatus(docId, versionId, DocumentStatus.CONVERTED, mdUrl);

        // 4. 分块并保存 knowledge_segment
        DocumentSplitter splitter = DocumentSplitters.recursive(CHUNK_SIZE, CHUNK_OVERLAP);
        List<TextSegment> chunks = splitter.split(Document.from(markdown));
        List<KnowledgeSegment> savedSegments = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            KnowledgeSegment segment = new KnowledgeSegment();
            segment.setText(chunks.get(i).text());
            segment.setChunkId(UUID.randomUUID().toString());
            segment.setDocumentId(docId);
            segment.setDocumentVersion(versionId);
            segment.setChunkOrder(i);
            segment.setStatus(SegmentStatus.STORED);
            segment.setSkipEmbedding(0);
            segmentMapper.insert(segment);
            savedSegments.add(segment);
        }
        updateStatus(docId, versionId, DocumentStatus.CHUNKED, null);
        log.info("文档 docId={} 分块完成，共 {} 块", docId, savedSegments.size());

        // 5. 批量 embedding 并写入向量库，chunkId 作为向量 ID
        List<String> ids = new ArrayList<>(savedSegments.size());
        List<TextSegment> toEmbed = new ArrayList<>(savedSegments.size());
        for (KnowledgeSegment segment : savedSegments) {
            Metadata metadata = new Metadata();
            metadata.put("chunkId", segment.getChunkId());
            metadata.put("documentId", docId);
            metadata.put("docTitle", docTitle);
            metadata.put("chunkOrder", segment.getChunkOrder());
            ids.add(segment.getChunkId());
            toEmbed.add(TextSegment.from(segment.getText(), metadata));
        }
        Response<List<Embedding>> embeddingResponse = embeddingModel.embedAll(toEmbed);
        embeddingStore.addAll(ids, embeddingResponse.content(), toEmbed);
        for (KnowledgeSegment segment : savedSegments) {
            segment.setEmbeddingId(segment.getChunkId());
            segment.setStatus(SegmentStatus.VECTOR_STORED);
            segmentMapper.updateById(segment);
        }
        updateStatus(docId, versionId, DocumentStatus.VECTOR_STORED, null);
        log.info("文档 docId={} 向量入库完成", docId);
    }

    private void updateStatus(Long docId, Long versionId, DocumentStatus status, String convertedDocUrl) {
        KnowledgeDocument document = new KnowledgeDocument();
        document.setDocId(docId);
        document.setStatus(status);
        documentMapper.updateById(document);

        KnowledgeDocumentVersion version = new KnowledgeDocumentVersion();
        version.setVersionId(versionId);
        version.setStatus(status);
        if (convertedDocUrl != null) {
            version.setConvertedDocUrl(convertedDocUrl);
        }
        versionMapper.updateById(version);
    }

    /**
     * 处理 Markdown 中的图片标签：把相对路径替换为 MinIO URL，alt 替换为视觉模型生成的描述
     */
    private String rewriteImages(String markdown, Map<String, String> imageUrls, Map<String, String> imageAlts) {
        Matcher matcher = IMAGE_TAG.matcher(markdown);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String path = matcher.group(2);
            String imageName = path.substring(path.lastIndexOf('/') + 1);
            String url = imageUrls.get(imageName);
            if (url == null) {
                matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group()));
                continue;
            }
            String alt = imageAlts.getOrDefault(imageName, "图片");
            matcher.appendReplacement(result, Matcher.quoteReplacement("![" + alt + "](" + url + ")"));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private Map<String, byte[]> unzip(byte[] zip) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    entries.put(entry.getName(), zis.readAllBytes());
                }
            }
        }
        return entries;
    }

    private String extractMarkdown(Map<String, byte[]> entries) {
        return entries.entrySet().stream()
                .filter(e -> e.getKey().toLowerCase(Locale.ROOT).endsWith(".md"))
                .findFirst()
                .map(e -> new String(e.getValue(), StandardCharsets.UTF_8))
                .orElse(null);
    }

    private String simpleName(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private boolean isImage(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".gif") || lower.endsWith(".bmp") || lower.endsWith(".webp");
    }

    private String imageMimeType(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        if (lower.endsWith(".bmp")) {
            return "image/bmp";
        }
        return "image/jpeg";
    }
}
