package com.shylesh.payout_service.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The bank's transfer API (processor-simulator). A transfer carries an Idempotency-Key derived
 * from the saga, so a transfer whose answer was lost can be sent again without paying twice; its
 * state is then polled by id.
 */
@Component
public class BankClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public BankClient(@Qualifier("bankRestClient") RestClient restClient, ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    public BankResponse transfer(String idempotencyKey, String bankAccount, BigDecimal amount, String currency, String reference) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bankAccount", bankAccount);
        body.put("amount", amount);
        body.put("currency", currency);
        body.put("reference", reference);
        return timed("transfer", () -> call(() -> restClient.post()
                .uri("/v1/transfers")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange((request, response) -> toResponse(response.getStatusCode().value(),
                        new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8)))));
    }

    public BankResponse status(String transferId) {
        return timed("status", () -> call(() -> restClient.get()
                .uri("/v1/transfers/{id}", transferId)
                .exchange((request, response) -> toResponse(response.getStatusCode().value(),
                        new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8)))));
    }

    private BankResponse timed(String operation, Supplier<BankResponse> call) {
        Timer.Sample sample = Timer.start(meterRegistry);
        BankResponse response = call.get();
        sample.stop(Timer.builder("bank.calls")
                .tag("operation", operation)
                .tag("outcome", response.outcome().name())
                .register(meterRegistry));
        return response;
    }

    private static BankResponse call(Supplier<BankResponse> request) {
        try {
            return request.get();
        } catch (RuntimeException e) {
            // timeouts and connection failures arrive wrapped (ResourceAccessException)
            Throwable cause = e.getCause() instanceof IOException ? e.getCause() : e;
            return BankResponse.unknown("unreachable", cause.getClass().getSimpleName() + ": " + cause.getMessage());
        }
    }

    private BankResponse toResponse(int status, String body) {
        JsonNode json = parse(body);
        String code = text(json, status >= 200 && status < 300 ? "failureCode" : "code");
        String message = text(json, "message");
        if (status >= 200 && status < 300) {
            return new BankResponse(BankResponse.Outcome.OK, status, text(json, "id"), text(json, "status"), code, null);
        }
        if (status == 402) {
            return new BankResponse(BankResponse.Outcome.DECLINED, status, null, null, code, message);
        }
        // 401/403: our credentials are wrong; 408/429: try later. Neither is the payout's fault.
        if (status >= 500 || status == 401 || status == 403 || status == 408 || status == 429) {
            return new BankResponse(BankResponse.Outcome.UNKNOWN, status, null, null, code, message);
        }
        return new BankResponse(BankResponse.Outcome.REJECTED, status, null, null, code, message);
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
