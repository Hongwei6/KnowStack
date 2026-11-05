package com.knowstack.engine.document.constant;

import com.knowstack.engine.document.entity.DocumentSplitParam;

/**
 * 文档类型切分预设（chunk_size / overlap 取推荐区间中值，单位：字符）
 *
 * 纯文本/文章   256-512 / 32-64   保持段落连贯，避免信息碎片化
 * 技术文档/代码 384-512 / 64      确保函数、类或代码块的完整性，避免被截断
 * 法律/合同文件 320-512 / 48-64   保持条款和逻辑推理的上下文，中文条文 320 效果更佳
 * FAQ/知识库    300-600 / 30-60   追求查询精准，每块聚焦一个问答对或事实
 * 长文本/论文   512-1024 / 100-200 需要更大上下文理解复杂论证和证据链
 */
public enum SplitPreset {

    PLAIN_TEXT("纯文本/文章", 384, 48),
    TECH_DOC("技术文档/代码", 448, 64),
    LEGAL("法律/合同文件", 320, 64),
    FAQ("FAQ/知识库", 400, 45),
    LONG_FORM("长文本/论文", 768, 150),
    DEFAULT("默认（智能）", 1000, 100);

    private final String label;
    private final int chunkSize;
    private final int overlap;

    SplitPreset(String label, int chunkSize, int overlap) {
        this.label = label;
        this.chunkSize = chunkSize;
        this.overlap = overlap;
    }

    public String getLabel() {
        return label;
    }

    public int getChunkSize() {
        return chunkSize;
    }

    public int getOverlap() {
        return overlap;
    }

    public DocumentSplitParam toParam() {
        return toParam(SplitType.SMART);
    }

    /** TXT 等纯文本无标题结构，用 LENGTH 按长度切分。 */
    public DocumentSplitParam toParam(SplitType type) {
        return new DocumentSplitParam(type.name(), chunkSize, overlap, null, null, null);
    }
}
