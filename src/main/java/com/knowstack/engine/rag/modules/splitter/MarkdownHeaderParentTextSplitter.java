package com.knowstack.engine.rag.modules.splitter;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown 父子分片器：
 * 按标题（默认 # ~ ###，可通过 titleLevel 调整）切出父分段（完整小节，skipEmbedding=1，
 * 不进向量库，检索命中子分段后可回查父分段补全上下文），小节内再用递归分片器切出
 * 子分段（skipEmbedding=0，参与嵌入）。输出顺序：父分段在前，紧随其全部子分段。
 *
 * @param titleLevel       父分段切分的标题层级（1~6）
 * @param includeParent    是否输出父分段（false 时只输出子分段）
 * @param keepHeaderInChild 子分段文本是否保留标题行
 */
public class MarkdownHeaderParentTextSplitter implements DocumentSplitter {

    private final int titleLevel;
    private final boolean includeParent;
    private final boolean keepHeaderInChild;
    private final DocumentSplitter childSplitter;

    /**
     * 智能模式：默认 3 级标题切父分段，输出父子两类分段
     */
    public MarkdownHeaderParentTextSplitter(int maxSegmentSize, int maxOverlapSize) {
        this(3, true, false, maxSegmentSize, maxOverlapSize);
    }

    public MarkdownHeaderParentTextSplitter(int titleLevel, boolean includeParent, boolean keepHeaderInChild,
                                             int maxSegmentSize, int maxOverlapSize) {
        this.titleLevel = Math.max(1, Math.min(6, titleLevel));
        this.includeParent = includeParent;
        this.keepHeaderInChild = keepHeaderInChild;
        this.childSplitter = DocumentSplitters.recursive(maxSegmentSize, maxOverlapSize);
    }

    @Override
    public List<TextSegment> split(Document document) {
        Pattern header = Pattern.compile("^(#{1," + titleLevel + "})\\s+.*");
        List<TextSegment> result = new ArrayList<>();
        for (String section : splitByHeaders(document.text(), header)) {
            String parentId = UUID.randomUUID().toString();
            String childText = keepHeaderInChild ? section : stripHeader(section, header);
            if (includeParent) {
                result.add(TextSegment.from(section, metadata(parentId, null)));
            }
            for (TextSegment child : childSplitter.split(Document.from(childText))) {
                result.add(TextSegment.from(child.text(), metadata(UUID.randomUUID().toString(), parentId)));
            }
        }
        return result;
    }

    private Metadata metadata(String chunkId, String parentChunkId) {
        Metadata metadata = new Metadata();
        metadata.put("chunkId", chunkId);
        if (parentChunkId != null) {
            metadata.put("parentChunkId", parentChunkId);
        }
        metadata.put("skipEmbedding", parentChunkId == null ? 1 : 0);
        return metadata;
    }

    /**
     * 按标题行切成小节，标题行保留在小节开头；无标题的前置内容单独成节
     */
    private List<String> splitByHeaders(String markdown, Pattern header) {
        List<String> sections = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : markdown.split("\n", -1)) {
            Matcher matcher = header.matcher(line);
            if (matcher.find() && matcher.group(1).length() <= titleLevel) {
                if (!current.toString().isBlank()) {
                    sections.add(current.toString().strip());
                }
                current = new StringBuilder();
            }
            current.append(line).append('\n');
        }
        if (!current.toString().isBlank()) {
            sections.add(current.toString().strip());
        }
        return sections;
    }

    /**
     * 去掉小节开头的标题行（keepHeaderInChild=false 时用于子分段文本）
     */
    private String stripHeader(String section, Pattern header) {
        String[] lines = section.split("\n", 2);
        if (lines.length == 2 && header.matcher(lines[0]).find()) {
            return lines[1].strip();
        }
        return section;
    }
}
