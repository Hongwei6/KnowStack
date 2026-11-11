package com.knowstack.engine.document.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MineruClientTest {

    @Test
    void failsWhenRunningWithoutProgress() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            server.createContext("/v1/tasks", exchange -> {
                if (exchange.getRequestMethod().equals("POST")) {
                    reply(exchange, 202, "{\"task_id\":\"stuck\",\"files\":[{\"upload\":{\"url\":\"" + base + "/upload\"}}]}");
                } else {
                    reply(exchange, 200, "{\"status\":\"running\",\"progress\":{\"completed\":0,\"failed\":0},\"files\":[{\"status\":\"running\"}]}");
                }
            });
            server.createContext("/upload", exchange -> reply(exchange, 200, ""));
            server.start();

            MineruClient client = new MineruClient(base, new ObjectMapper(), Duration.ofMillis(1));
            assertThatThrownBy(() -> client.parseToZip("stuck.pdf", new byte[]{1}))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("运行中无进度").hasMessageContaining("stuck");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void pendingThenRunningThenCompletedDoesNotCountAsStall() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            AtomicInteger polls = new AtomicInteger();
            server.createContext("/v1/tasks", exchange -> {
                if (exchange.getRequestMethod().equals("POST")) {
                    reply(exchange, 202, "{\"task_id\":\"ok\",\"files\":[{\"upload\":{\"url\":\"" + base + "/upload\"}}]}");
                } else {
                    int poll = polls.incrementAndGet();
                    String status = poll == 1 ? "pending" : poll == 2 ? "running" : "completed";
                    reply(exchange, 200, "{\"status\":\"" + status + "\",\"progress\":{\"completed\":0,\"failed\":0},\"files\":[{\"status\":\"" + status + "\",\"result_url\":\"" + base + "/result\"}]}");
                }
            });
            server.createContext("/upload", exchange -> reply(exchange, 200, ""));
            server.createContext("/result", exchange -> reply(exchange, 200, "zip"));
            server.start();

            MineruClient client = new MineruClient(base, new ObjectMapper(), Duration.ofMillis(1));
            assertThat(client.parseToZip("ok.pdf", new byte[]{1})).isEqualTo("zip".getBytes(StandardCharsets.UTF_8));
            assertThat(polls.get()).isEqualTo(3);
        } finally {
            server.stop(0);
        }
    }

    private static void reply(HttpExchange exchange, int status, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var body = exchange.getResponseBody()) {
            body.write(bytes);
        }
    }
}
