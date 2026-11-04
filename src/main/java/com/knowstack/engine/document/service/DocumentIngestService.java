package com.knowstack.engine.document.service;

import com.knowstack.engine.document.constant.DocumentStatus;
import com.knowstack.engine.document.entity.KnowledgeDocument;
import com.knowstack.engine.document.entity.KnowledgeDocumentVersion;
import com.knowstack.engine.document.event.DocumentEvents;
import com.knowstack.engine.document.mapper.KnowledgeDocumentMapper;
import com.knowstack.engine.document.mapper.KnowledgeDocumentVersionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 上传事件触发 PDF 转换；成功后发布转换事件，切分与向量化由各自监听器处理。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentIngestService {

    private static final Pattern IMAGE_TAG = Pattern.compile("!\\[(.*?)]\\(([^)]+)\\)");

    private final MineruClient mineruClient;
    private final MinioService minioService;
    private final VisionService visionService;
    private final ApplicationEventPublisher events;
    private final DocumentProcessingLock processingLock;
    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeDocumentVersionMapper versionMapper;

    /** 上传事件或恢复任务触发 PDF 转换；成功后发布 CONVERTED 事件。 */
    public void convert(Long docId) {
        boolean converted = processingLock.execute(docId, () -> doConvert(docId));
        if (converted) {
            log.info("发布文档 CONVERTED 事件 docId={}", docId);
            events.publishEvent(new DocumentEvents.Converted(docId));
        }
    }

    private boolean doConvert(Long docId) throws IOException {
        KnowledgeDocument document = documentMapper.selectById(docId);
        if (document == null || (document.getStatus() != DocumentStatus.UPLOADED
                && document.getStatus() != DocumentStatus.CONVERTING)) {
            return false;
        }
        Long versionId = document.getCurrentVersionId();
        KnowledgeDocumentVersion source = versionMapper.selectById(versionId);
        if (source == null || source.getDocUrl() == null) {
            throw new IllegalStateException("原始 PDF 不存在: " + docId);
        }
        log.info("开始 PDF 转换 docId={}, versionId={}", docId, versionId);
        String fileName = document.getDocTitle();
        byte[] pdf = minioService.downloadBytes(minioService.objectName(source.getDocUrl()));
        String storageKey = docId + "/" + versionId;
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

        log.info("文档 docId={} 转换完成", docId);
        return true;
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
