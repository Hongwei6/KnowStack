package com.knowstack.engine.document.service;


import com.knowstack.engine.document.constant.FileType;
import com.knowstack.engine.document.constant.KnowledgeBaseType;
import com.knowstack.engine.document.entity.KnowledgeDocument;

import java.io.InputStream;

/**
 * 表格实现把 Excel 首张工作表或 CSV 记录写入 MySQL，返回 null；
 * PDF 的 MinerU 解析由 DocumentIngestService 单独负责。
 */
public interface FileProcessService {
    /**
     * 导入已持久化的文档版本。
     *
     * @return 转换后的 URL；无需转换时为 null
     */
    String processDocument(KnowledgeDocument document, InputStream inputStream);

    /**
     * 判断是否支持该文件
     */
    boolean supports(FileType fileType, KnowledgeBaseType knowledgeBaseType);
}

