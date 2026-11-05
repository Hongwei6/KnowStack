package com.knowstack.engine.document.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/** MinerU 4.x 自托管 API：创建任务、上传 PDF/Word、等待解析并下载结果 ZIP。 */
@Service
public class MineruClient {

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper;
    private final String baseUrl;

    public MineruClient(@Value("${mineru.base-url}") String baseUrl, ObjectMapper mapper) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.mapper = mapper;
    }

    public byte[] parseToZip(String fileName, byte[] content) {
        if (fileName == null || !fileName.toLowerCase(java.util.Locale.ROOT).matches(".*\\.(pdf|doc|docx)")) {
            throw new IllegalArgumentException("MinerU conversion requires a PDF or Word file");
        }
        try {
            byte[] body = mapper.writeValueAsBytes(Map.of("tier", "standard", "files",
                    java.util.List.of(Map.of("file_name", fileName, "bytes", content.length))));
            JsonNode created = json(HttpRequest.newBuilder(uri("/v1/tasks"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), 202);
            String taskId = created.path("task_id").asText();
            JsonNode upload = created.path("files").path(0).path("upload");
            if (taskId.isBlank() || upload.path("url").asText().isBlank()) {
                throw new IOException("MinerU 未返回任务 ID 或上传地址: " + created);
            }
            HttpRequest.Builder uploadRequest = HttpRequest.newBuilder(uri(upload.path("url").asText()))
                    .timeout(Duration.ofMinutes(2)).PUT(HttpRequest.BodyPublishers.ofByteArray(content));
            upload.path("headers").fields().forEachRemaining(header -> uploadRequest.header(header.getKey(), header.getValue().asText()));
            response(uploadRequest.build(), 200);

            long deadline = System.nanoTime() + Duration.ofMinutes(10).toNanos();
            while (System.nanoTime() < deadline) {
                JsonNode task = json(HttpRequest.newBuilder(uri("/v1/tasks/" + taskId))
                        .timeout(Duration.ofSeconds(15)).GET().build(), 200);
                JsonNode file = task.path("files").path(0);
                if ("completed".equals(file.path("status").asText())) {
                    String resultUrl = file.path("result_url").asText();
                    if (resultUrl.isBlank()) throw new IOException("MinerU 完成但未提供结果下载地址: " + task);
                    return response(HttpRequest.newBuilder(uri(resultUrl))
                            .timeout(Duration.ofMinutes(2)).GET().build(), 200);
                }
                if ("failed".equals(file.path("status").asText()) || "canceled".equals(file.path("status").asText())) {
                    throw new IOException("MinerU 文件解析失败: " + file.path("error"));
                }
                if ("failed".equals(task.path("status").asText()) || "canceled".equals(task.path("status").asText())) {
                    throw new IOException("MinerU 任务失败: " + task.path("error"));
                }
                Thread.sleep(2000);
            }
            throw new IOException("MinerU 任务超时: " + taskId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("MinerU 解析被中断", e);
        } catch (IOException e) {
            throw new IllegalStateException("MinerU 解析失败: " + e.getMessage(), e);
        }
    }

    private JsonNode json(HttpRequest request, int expectedStatus) throws IOException, InterruptedException {
        return mapper.readTree(response(request, expectedStatus));
    }

    private byte[] response(HttpRequest request, int expectedStatus) throws IOException, InterruptedException {
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != expectedStatus) {
            throw new IOException("MinerU HTTP " + response.statusCode() + ": "
                    + new String(response.body(), StandardCharsets.UTF_8));
        }
        return response.body();
    }

    private URI uri(String path) {
        URI url = URI.create(path);
        return url.isAbsolute() ? url : URI.create(baseUrl + (path.startsWith("/") ? path : "/" + path));
    }
}
