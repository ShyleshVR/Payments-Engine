package com.shylesh.payment_service.processor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import lombok.extern.slf4j.Slf4j;

import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The card processor's API. Every call carries an Idempotency-Key chosen by the saga (derived
 * from the saga id and step), so retrying a call whose answer was lost can never charge or
 * refund twice. Calls go through the "processor" circuit breaker: while the processor keeps
 * failing, calls are refused locally (UNKNOWN, circuit_open) and the saga worker backs off,
 * instead of every saga waiting out its own timeout against a dead endpoint.
 */
@Slf4j
@Component
public class ProcessorClient {

    private final RestClient restClient;
    private final CircuitBreaker circuitBreaker;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public ProcessorClient(RestClient.Builder restClientBuilder, ProcessorProperties properties,
                           CircuitBreakerRegistry circuitBreakerRegistry, ObjectMapper objectMapper,
                           MeterRegistry meterRegistry) {
        // The client gets an executor of its own: without one, Spring writes every request body on
        // a new thread (SimpleAsyncTaskExecutor), and under load creating those threads cost more
        // CPU than the rest of the saga's work.
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .executor(httpExecutor())
                .build());
        requestFactory.setReadTimeout(properties.readTimeout());
        this.restClient = restClientBuilder
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .defaultHeader("X-Api-Key", properties.apiKey())
                .build();
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker("processor");
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    /** Reused threads for the HTTP client's work (idle ones are dropped after a minute). */
    static ExecutorService httpExecutor() {
        AtomicInteger counter = new AtomicInteger();
        return Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "processor-http-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    public ProcessorResponse authorize(String idempotencyKey, String paymentMethod, BigDecimal amount,
                                       String currency, String reference) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("paymentMethod", paymentMethod);
        body.put("amount", amount);
        body.put("currency", currency);
        body.put("reference", reference);
        return call("authorize", () -> post("/v1/authorizations", idempotencyKey, body));
    }

    public ProcessorResponse capture(String idempotencyKey, String authorizationId) {
        return call("capture", () -> post("/v1/authorizations/{id}/capture", idempotencyKey, null, authorizationId));
    }

    /**
     * Cancels the authorization request sent with originalKey, whether or not it was processed or
     * even received: voids it if it was approved, and makes it fail if it arrives later.
     */
    public ProcessorResponse reverse(String idempotencyKey, String originalKey) {
        return call("reverse", () -> post("/v1/reversals", idempotencyKey, Map.of("originalIdempotencyKey", originalKey)));
    }

    public ProcessorResponse refund(String idempotencyKey, String authorizationId, BigDecimal amount) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("authorizationId", authorizationId);
        body.put("amount", amount);
        return call("refund", () -> post("/v1/refunds", idempotencyKey, body));
    }

    private ProcessorResponse call(String operation, Supplier<ProcessorResponse> request) {
        Timer.Sample sample = Timer.start(meterRegistry);
        ProcessorResponse response;
        try {
            response = circuitBreaker.executeSupplier(() -> {
                ProcessorResponse result = request.get();
                if (result.outcome() == ProcessorResponse.Outcome.UNKNOWN) {
                    throw new ProcessorUnavailableException(result);
                }
                return result;
            });
        } catch (ProcessorUnavailableException e) {
            response = e.response();
        } catch (CallNotPermittedException e) {
            response = ProcessorResponse.unknown("circuit_open", "Processor circuit breaker is open");
        }
        sample.stop(Timer.builder("processor.calls")
                .tag("operation", operation)
                .tag("outcome", response.outcome().name())
                .register(meterRegistry));
        return response;
    }

    /** uriTemplate with {placeholders}: metrics tag requests by the template, not by each id. */
    private ProcessorResponse post(String uriTemplate, String idempotencyKey, Object body, Object... uriVariables) {
        try {
            RestClient.RequestBodySpec spec = restClient.post()
                    .uri(uriTemplate, uriVariables)
                    .header("Idempotency-Key", idempotencyKey)
                    .contentType(MediaType.APPLICATION_JSON);
            if (body != null) {
                spec.body(body);
            }
            return spec.exchange((request, response) -> toResponse(
                    response.getStatusCode().value(),
                    new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8)));
        } catch (RuntimeException e) {
            // timeouts and connection failures arrive wrapped (ResourceAccessException)
            Throwable cause = e.getCause() instanceof IOException ? e.getCause() : e;
            return ProcessorResponse.unknown("unreachable", cause.getClass().getSimpleName() + ": " + cause.getMessage());
        }
    }

    private ProcessorResponse toResponse(int status, String body) {
        JsonNode json = parse(body);
        String code = text(json, "code");
        String message = text(json, "message");
        if (status >= 200 && status < 300) {
            String id = text(json, "id");
            return new ProcessorResponse(ProcessorResponse.Outcome.SUCCEEDED, status, null,
                    id != null ? id : text(json, "authorizationId"), null);
        }
        if (status == 402) {
            return new ProcessorResponse(ProcessorResponse.Outcome.DECLINED, status, code, null, message);
        }
        // 401/403: our credentials are wrong; 408/429: try later. Neither is the payment's fault.
        if (status >= 500 || status == 401 || status == 403 || status == 408 || status == 429) {
            return new ProcessorResponse(ProcessorResponse.Outcome.UNKNOWN, status, code, null, message);
        }
        return new ProcessorResponse(ProcessorResponse.Outcome.REJECTED, status, code, null, message);
    }

    private JsonNode parse(String body) {
        try {
            return body == null || body.isBlank() ? null : objectMapper.readTree(body);
        } catch (IOException e) {
            return null;
        }
    }

    private static String text(JsonNode json, String field) {
        return json == null || json.get(field) == null || json.get(field).isNull() ? null : json.get(field).asText();
    }
}
