package com.shylesh.webhook_service.http;

import com.shylesh.webhook_service.config.WebhookProperties;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Posts one signed webhook. Never throws: every outcome, including timeouts and connection
 * errors, comes back as an HttpOutcome so the caller always records the attempt.
 */
@Component
@RequiredArgsConstructor
public class WebhookHttpClient {

    /** Enough of the merchant's response body to explain a failure, never the whole thing. */
    static final int MAX_RESPONSE_SNIPPET_BYTES = 512;

    private final HttpClient merchantHttpClient;
    private final WebhookProperties properties;

    public HttpOutcome post(URI url, Map<String, String> headers, byte[] body) {
        long start = System.nanoTime();

        HttpRequest request;
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(url)
                    .timeout(properties.delivery().readTimeout())
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body));
            headers.forEach(builder::header);
            request = builder.build();
        } catch (IllegalArgumentException e) {
            return HttpOutcome.permanent("Invalid request: " + e.getMessage(), elapsedMs(start));
        }

        try {
            HttpResponse<InputStream> response = merchantHttpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            String snippet = readSnippet(response.body());
            return HttpOutcome.forStatus(response.statusCode(), snippet, elapsedMs(start));
        } catch (HttpTimeoutException e) {
            return HttpOutcome.retryable("Timed out: " + e.getMessage(), elapsedMs(start));
        } catch (ConnectException e) {
            return HttpOutcome.retryable("Connection failed: " + describe(e), elapsedMs(start));
        } catch (IOException e) {
            return HttpOutcome.retryable("I/O error: " + describe(e), elapsedMs(start));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return HttpOutcome.retryable("Interrupted while sending", elapsedMs(start));
        }
    }

    private static String readSnippet(InputStream body) {
        try (body) {
            byte[] bytes = body.readNBytes(MAX_RESPONSE_SNIPPET_BYTES);
            return new String(bytes, StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            return null;
        }
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
