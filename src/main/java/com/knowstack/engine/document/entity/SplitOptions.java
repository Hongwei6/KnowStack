package com.knowstack.engine.document.entity;

/**
 * 前端传入的切分选项：预设、切分方式及各方式所需参数。
 * 上传与手动重切共用；upload 场景 chunkSize/overlap 由预设提供。
 */
public record SplitOptions(String docType,
                           String splitType,
                           Integer chunkSize,
                           Integer overlap,
                           Integer titleLevel,
                           String separator,
                           String regex) {

    public static SplitOptions empty() {
        return new SplitOptions(null, null, null, null, null, null, null);
    }
}
