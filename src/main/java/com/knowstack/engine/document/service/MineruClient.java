package com.knowstack.engine.document.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class MineruClient {
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json;
    private final String baseUrl;

    public MineruClient(ObjectMapper json, @Value("${mineru.base-url}") String baseUrl) {
        this.json = json;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    /** Returns the complete MinerU structured result; page blocks include extracted text and layout. */
    public JsonNode convertPdf(Path pdf) throws IOException, InterruptedException {
        if (!pdf.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")) {
            throw new IllegalArgumentException("MinerU conversion requires a PDF file");
        }
        String boundary = UUID.randomUUID().toString();
        String filename = "document.pdf";
        byte[] prefix = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"files\"; filename=\""
                + filename + "\"\r\nContent-Type: application/pdf\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] suffix = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        HttpRequest upload = HttpRequest.newBuilder(URI.create(baseUrl + "/gradio_api/upload"))
                .timeout(Duration.ofMinutes(5))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.concat(HttpRequest.BodyPublishers.ofByteArray(prefix),
                        HttpRequest.BodyPublishers.ofFile(pdf), HttpRequest.BodyPublishers.ofByteArray(suffix)))
                .build();
        JsonNode uploaded = json.readTree(send(upload));
        if (!uploaded.isArray() || uploaded.isEmpty() || !uploaded.get(0).isTextual()) {
            throw new IOException("MinerU upload returned no file path");
        }
        String payload = json.createObjectNode().set("data", json.createArrayNode()
                .add(json.createObjectNode().put("path", uploaded.get(0).asText())
                        .put("orig_name", filename)
                        .set("meta", json.createObjectNode().put("_type", "gradio.FileData")))
                .add(2).add("").add(false)).toString();
        HttpRequest start = HttpRequest.newBuilder(URI.create(baseUrl + "/gradio_api/call/convert_handler"))
                .timeout(Duration.ofMinutes(2))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        JsonNode event = json.readTree(send(start));
        if (!event.path("event_id").isTextual()) {
            throw new IOException("MinerU did not return a conversion event");
        }
        HttpRequest result = HttpRequest.newBuilder(URI.create(baseUrl + "/gradio_api/call/convert_handler/"
                + event.get("event_id").asText())).timeout(Duration.ofMinutes(10)).GET().build();
        HttpResponse<java.io.InputStream> response = http.send(result, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IOException("MinerU result request failed: HTTP " + response.statusCode());
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            String eventType = "";
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("event: ")) {
                    eventType = line.substring(7);
                } else if (line.startsWith("data: ")) {
                    if ("error".equals(eventType)) {
                        throw new IOException("MinerU conversion failed: " + line.substring(6));
                    }
                    if ("complete".equals(eventType)) {
                        JsonNode resultData = json.readTree(line.substring(6));
                        if (resultData.isArray() && resultData.size() > 15 && resultData.get(15).isTextual()) {
                            return json.readTree(resultData.get(15).asText());
                        }
                        throw new IOException("MinerU returned no structured document");
                    }
                }
            }
        }
        throw new IOException("MinerU conversion ended without a result");
    }

    private String send(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("MinerU request failed: HTTP " + response.statusCode() + " " + response.body());
        }
        return response.body();
    }
}
