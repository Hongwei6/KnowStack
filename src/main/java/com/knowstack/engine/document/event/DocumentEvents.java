package com.knowstack.engine.document.event;

/** 阶段事件只传文档 ID，处理器按当前持久化状态决定是否执行。 */
public final class DocumentEvents {

    private DocumentEvents() {
    }

    public record Uploaded(Long documentId) {
    }

    public record Converted(Long documentId) {
    }

    public record Chunked(Long documentId) {
    }
}
