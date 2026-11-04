package com.knowstack.engine.document.service;

import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.Callable;

/** 同一文档的索引构建与手动重切共用一把锁；Redisson 看门狗在任务运行期间续期。 */
@Component
@RequiredArgsConstructor
public class DocumentProcessingLock {

    private final RedissonClient redissonClient;

    public <T> T execute(Long documentId, Callable<T> work) {
        RLock lock = redissonClient.getLock("document:processing:" + documentId);
        if (!lock.tryLock()) {
            throw new DocumentBusyException(documentId);
        }
        try {
            return work.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("文档处理失败: " + documentId, e);
        } finally {
            lock.unlock();
        }
    }

    public static class DocumentBusyException extends IllegalStateException {
        public DocumentBusyException(Long documentId) {
            super("文档正在处理: " + documentId);
        }
    }
}
