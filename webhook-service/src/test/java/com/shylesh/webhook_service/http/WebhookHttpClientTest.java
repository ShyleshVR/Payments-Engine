package com.shylesh.webhook_service.http;

import com.shylesh.webhook_service.TestProperties;
import com.shylesh.webhook_service.config.WebhookHttpConfig;
import com.shylesh.webhook_service.config.WebhookProperties;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Exercises the real JDK HTTP client against a local HTTP server. */
class WebhookHttpClientTest {

    private HttpServer server;
    private WebhookHttpClient client;
    private final AtomicReference<String> receivedSignature = new AtomicReference<>();
    private final AtomicReference<String> receivedBody = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/status", exchange -> {
            receivedSignature.set(exchange.getRequestHeaders().getFirst("X-Webhook-Signature"));
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            int status = Integer.parseInt(exchange.getRequestURI().getQuery().replace("code=", ""));
            byte[] response = ("merchant says " + status).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, response.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(response);
            }
        });
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(3_000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.createContext("/huge-error", exchange -> {
            byte[] response = "x".repeat(100_000).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, response.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(response);
            }
        });
        server.start();

        WebhookProperties properties = TestProperties.with(false, true);
        client = new WebhookHttpClient(new WebhookHttpConfig().merchantHttpClient(properties), properties);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private URI url(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    private HttpOutcome post(String path) {
        return client.post(url(path), Map.of("X-Webhook-Signature", "sha256=abc", "Content-Type", "application/json"),
                "{\"a\":1}".getBytes(StandardCharsets.UTF_8));
    }

    @ParameterizedTest
    @CsvSource({
            "200, SUCCESS", "204, SUCCESS",
            "500, RETRYABLE", "503, RETRYABLE", "408, RETRYABLE", "429, RETRYABLE",
            "400, PERMANENT", "401, PERMANENT", "404, PERMANENT", "410, PERMANENT", "301, PERMANENT"
    })
    void classifiesMerchantResponses(int status, HttpOutcome.Result expected) {
        HttpOutcome outcome = post("/status?code=" + status);

        assertThat(outcome.result()).isEqualTo(expected);
        assertThat(outcome.statusCode()).isEqualTo(status);
        if (expected != HttpOutcome.Result.SUCCESS) {
            assertThat(outcome.error()).startsWith("HTTP " + status);
        }
    }

    @Test
    void sendsHeadersAndExactBody() {
        post("/status?code=200");

        assertThat(receivedSignature.get()).isEqualTo("sha256=abc");
        assertThat(receivedBody.get()).isEqualTo("{\"a\":1}");
    }

    @Test
    void readTimeoutIsRetryable() {
        HttpOutcome outcome = post("/slow");

        assertThat(outcome.result()).isEqualTo(HttpOutcome.Result.RETRYABLE);
        assertThat(outcome.statusCode()).isNull();
        assertThat(outcome.error()).startsWith("Timed out");
        assertThat(outcome.durationMs()).isLessThan(2_900);
    }

    @Test
    void connectionRefusedIsRetryable() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }

        HttpOutcome outcome = client.post(URI.create("http://127.0.0.1:" + closedPort + "/hook"), Map.of(), new byte[0]);

        assertThat(outcome.result()).isEqualTo(HttpOutcome.Result.RETRYABLE);
        assertThat(outcome.statusCode()).isNull();
    }

    @Test
    void readsOnlyASnippetOfALargeErrorBody() {
        HttpOutcome outcome = post("/huge-error");

        assertThat(outcome.result()).isEqualTo(HttpOutcome.Result.RETRYABLE);
        assertThat(outcome.error().length()).isLessThanOrEqualTo("HTTP 500: ".length() + WebhookHttpClient.MAX_RESPONSE_SNIPPET_BYTES);
    }
}
