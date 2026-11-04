package com.knowstack.engine.rag.modules.splitter;

import com.knowstack.engine.document.constant.SplitType;
import com.knowstack.engine.document.entity.DocumentSplitParam;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentByRegexSplitter;
import dev.langchain4j.data.document.splitter.DocumentByWordSplitter;

/**
 * @Author Hollis
 */
public class DocumentSplitterFactory {
    public static DocumentSplitter getInstance(DocumentSplitParam documentSplitParam) {
        // 参数兜底：未填的尺寸/层级取默认值
        int chunkSize = documentSplitParam.chunkSize() != null ? documentSplitParam.chunkSize() : 1000;
        int overlap = documentSplitParam.overlap() != null && documentSplitParam.overlap() > 0
                ? documentSplitParam.overlap() : (int) (chunkSize * 0.1);

        if (SplitType.TITLE.name().equals(documentSplitParam.splitType())) {
            int titleLevel = documentSplitParam.titleLevel() != null ? documentSplitParam.titleLevel() : 3;
            return new MarkdownHeaderParentTextSplitter(titleLevel, false, false, chunkSize, overlap);
        }

        if (SplitType.LENGTH.name().equals(documentSplitParam.splitType())) {
            return new DocumentByWordSplitter(chunkSize, overlap);
        }

        if (SplitType.SEPARATOR.name().equals(documentSplitParam.splitType())) {
            return new DocumentByRegexSplitter(documentSplitParam.separator(), "\\n\\n", chunkSize, overlap);
        }

        if (SplitType.REGEX.name().equals(documentSplitParam.splitType())) {
            return new DocumentByRegexSplitter(documentSplitParam.regex(), "\\n\\n", chunkSize, overlap);
        }

        if (SplitType.SMART.name().equals(documentSplitParam.splitType())) {
            return new MarkdownHeaderParentTextSplitter(chunkSize, overlap);
        }

        return null;
    }
}
