package com.knowstack.engine.document.service;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.SetBucketPolicyArgs;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * MinIO 对象存储服务：上传文件并返回可直接访问的 URL
 */
@Slf4j
@Service
public class MinioService {

    private final MinioClient client;
    private final String endpoint;
    private final String bucket;
    private volatile boolean bucketReady;

    public MinioService(MinioClient client,
                        @Value("${minio.endpoint}") String endpoint,
                        @Value("${minio.bucket}") String bucket) {
        this.client = client;
        this.endpoint = endpoint.replaceAll("/+$", "");
        this.bucket = bucket;
    }

    /**
     * 上传字节数组，返回 {endpoint}/{bucket}/{objectName} 形式的 URL
     */
    public String upload(String objectName, byte[] data, String contentType) {
        ensureBucket();
        try {
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectName)
                    .stream(new ByteArrayInputStream(data), data.length, -1)
                    .contentType(contentType)
                    .build());
        } catch (Exception e) {
            throw new RuntimeException("MinIO 上传失败: " + objectName, e);
        }
        log.info("MinIO 上传成功: {}/{}/{}", endpoint, bucket, objectName);
        return endpoint + "/" + bucket + "/" + objectName;
    }

    /** 下载原始文档供异步转换使用；事件中不携带文件字节。 */
    public byte[] downloadBytes(String objectName) {
        try (InputStream is = client.getObject(
                GetObjectArgs.builder().bucket(bucket).object(objectName).build())) {
            return is.readAllBytes();
        } catch (Exception e) {
            throw new RuntimeException("MinIO 下载失败: " + objectName, e);
        }
    }

    /**
     * 下载对象内容为文本（UTF-8）
     */
    public String downloadText(String objectName) {
        try (InputStream is = client.getObject(
                GetObjectArgs.builder().bucket(bucket).object(objectName).build())) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("MinIO 下载失败: " + objectName, e);
        }
    }

    /**
     * 从本服务生成的 URL 中还原对象名
     */
    public String objectName(String url) {
        String prefix = endpoint + "/" + bucket + "/";
        if (url == null || !url.startsWith(prefix)) {
            throw new IllegalArgumentException("无法解析文档URL: " + url);
        }
        return url.substring(prefix.length());
    }

    /**
     * ponytail: 内网知识库，桶策略直接设为公共读，Markdown/图片 URL 可在浏览器直接打开；
     * 需要权限控制时再改为按前缀授权或预签名 URL
     */
    private void ensureBucket() {
        if (bucketReady) {
            return;
        }
        synchronized (this) {
            if (bucketReady) {
                return;
            }
            try {
                if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                    client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                }
                client.setBucketPolicy(SetBucketPolicyArgs.builder()
                        .bucket(bucket)
                        .config(publicReadPolicy(bucket))
                        .build());
                bucketReady = true;
            } catch (Exception e) {
                throw new RuntimeException("MinIO 桶初始化失败: " + bucket, e);
            }
        }
    }

    private String publicReadPolicy(String bucketName) {
        return "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"AWS\":[\"*\"]},"
                + "\"Action\":[\"s3:GetObject\"],\"Resource\":[\"arn:aws:s3:::" + bucketName + "/*\"]}]}";
    }
}
