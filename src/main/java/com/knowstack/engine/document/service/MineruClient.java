package com.knowstack.engine.document.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * MinerU FastAPI 客户端
 * 调用 POST /file_parse，response_format_zip=true + return_images=true，
 * 返回包含 Markdown 与图片的 ZIP，避免 fast api 默认只返回 markdown 丢失图片的问题
 */
@Slf4j
@Service
public class MineruClient {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final String baseUrl;

    public MineruClient(@Value("${mineru.base-url}") String baseUrl) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    /**
     * 解析 PDF，返回 ZIP 字节数组（内含 .md 文件与图片文件）
     */
    public byte[] parseToZip(String fileName, byte[] pdf) {
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            throw new IllegalArgumentException("MinerU conversion requires a PDF file");
        }
        String boundary = UUID.randomUUID().toString();
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/file_parse"))
                .timeout(Duration.ofMinutes(10))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArrays(multipart(boundary, fileName, pdf)))
                .build();
        log.info("调用 MinerU 解析: {}, 文件大小 {} bytes", baseUrl + "/file_parse", pdf.length);
        try {
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                String body = new String(response.body(), StandardCharsets.UTF_8);
                log.error("MinerU 解析失败，状态码: {}, 响应: {}", response.statusCode(), body);
                throw new IOException("MinerU 解析失败: HTTP " + response.statusCode());
            }
            log.info("MinerU 解析成功，ZIP 大小 {} bytes", response.body().length);
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("MinerU 解析被中断", e);
        } catch (IOException e) {
            throw new RuntimeException("MinerU 解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 手工构建 multipart/form-data：files 文件 + backend/response_format_zip/return_images 等参数
     */
    private List<byte[]> multipart(String boundary, String fileName, byte[] pdf) {
        String prefix = "--" + boundary + "\r\n";
        String suffix = "\r\n";
        List<byte[]> parts = new ArrayList<>();
        parts.add((prefix + "Content-Disposition: form-data; name=\"files\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: application/pdf\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        parts.add(pdf);
        parts.add((suffix + prefix + "Content-Disposition: form-data; name=\"backend\"\r\n\r\npipeline").getBytes(StandardCharsets.UTF_8));
        parts.add((suffix + prefix + "Content-Disposition: form-data; name=\"response_format_zip\"\r\n\r\ntrue").getBytes(StandardCharsets.UTF_8));
        parts.add((suffix + prefix + "Content-Disposition: form-data; name=\"return_images\"\r\n\r\ntrue").getBytes(StandardCharsets.UTF_8));
        parts.add((suffix + prefix + "Content-Disposition: form-data; name=\"return_model_output\"\r\n\r\nfalse").getBytes(StandardCharsets.UTF_8));
        parts.add((suffix + prefix + "Content-Disposition: form-data; name=\"return_middle_json\"\r\n\r\nfalse").getBytes(StandardCharsets.UTF_8));
        parts.add(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return parts;
    }
}
