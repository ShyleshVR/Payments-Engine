package com.shylesh.processor_simulator.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shylesh.processor_simulator.config.ProcessorProperties;
import com.shylesh.processor_simulator.persistence.OperationType;
import com.shylesh.processor_simulator.service.FaultInjector;
import com.shylesh.processor_simulator.service.IdempotentExecutor;
import com.shylesh.processor_simulator.service.PaymentMethodBehaviour;
import com.shylesh.processor_simulator.service.ProcessorResult;
import com.shylesh.processor_simulator.service.ProcessorService;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * A card processor's API, shaped like a real one: every POST takes an Idempotency-Key, declines
 * are 402 with a code, and the outcome of any request can be looked up by its key.
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class ProcessorController {

    private static final String KEY_HEADER = "Idempotency-Key";
    private static final String KEY_PATTERN = "[A-Za-z0-9:._-]{1,100}";

    private final ProcessorService processorService;
    private final IdempotentExecutor executor;
    private final FaultInjector faultInjector;
    private final ProcessorProperties properties;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;

    @PostMapping("/authorizations")
    public ResponseEntity<String> authorize(@RequestHeader(KEY_HEADER) @Pattern(regexp = KEY_PATTERN) String key,
                                            @Valid @RequestBody Requests.Authorize request) {
        String hash = hash("AUTHORIZE", request.paymentMethod(), money(request.amount()), request.currency(), request.reference());
        Outcome outcome = execute(key, OperationType.AUTHORIZE, hash, request.paymentMethod(),
                () -> processorService.authorize(request.paymentMethod(), request.amount(), request.currency(), request.reference()));

        // pm_card_timeout_once: the authorization is committed, but the answer comes too late
        // for the caller, which must find out by retrying with the same key.
        if (outcome.fresh() && outcome.response().getStatusCode().value() == 201
                && PaymentMethodBehaviour.TIMEOUT_ONCE.paymentMethod().equals(request.paymentMethod())) {
            log.info("pm_card_timeout_once: delaying the answer for key {} by {}", key, properties.timeoutOnceDelay());
            sleep(properties.timeoutOnceDelay());
        }
        return outcome.response();
    }

    @PostMapping("/authorizations/{authorizationId}/capture")
    public ResponseEntity<String> capture(@RequestHeader(KEY_HEADER) @Pattern(regexp = KEY_PATTERN) String key,
                                          @PathVariable String authorizationId) {
        return run(key, OperationType.CAPTURE, hash("CAPTURE", authorizationId), paymentMethodOf(authorizationId),
                () -> processorService.capture(authorizationId));
    }

    @PostMapping("/authorizations/{authorizationId}/void")
    public ResponseEntity<String> voidAuthorization(@RequestHeader(KEY_HEADER) @Pattern(regexp = KEY_PATTERN) String key,
                                                    @PathVariable String authorizationId) {
        return run(key, OperationType.VOID, hash("VOID", authorizationId), paymentMethodOf(authorizationId),
                () -> processorService.voidAuthorization(authorizationId));
    }

    @PostMapping("/refunds")
    public ResponseEntity<String> refund(@RequestHeader(KEY_HEADER) @Pattern(regexp = KEY_PATTERN) String key,
                                         @Valid @RequestBody Requests.Refund request) {
        return run(key, OperationType.REFUND, hash("REFUND", request.authorizationId(), money(request.amount())),
                paymentMethodOf(request.authorizationId()),
                () -> processorService.refund(request.authorizationId(), request.amount()));
    }

    @PostMapping("/reversals")
    public ResponseEntity<String> reverse(@RequestHeader(KEY_HEADER) @Pattern(regexp = KEY_PATTERN) String key,
                                          @Valid @RequestBody Requests.Reversal request) {
        if (key.equals(request.originalIdempotencyKey())) {
            return json(ProcessorResult.error(422, "invalid_request", "A reversal needs its own Idempotency-Key"));
        }
        return run(key, OperationType.REVERSAL, hash("REVERSAL", request.originalIdempotencyKey()), null,
                () -> processorService.reverse(request.originalIdempotencyKey()));
    }

    /** Status inquiry: what happened to the request sent with this key (404: never received). */
    @GetMapping("/operations/{key}")
    public ResponseEntity<?> inquiry(@PathVariable @Pattern(regexp = KEY_PATTERN) String key) {
        Optional<Map<String, Object>> operation = processorService.inquiry(key);
        return operation.<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(404)
                        .body(ProcessorResult.error("ERROR", "operation_not_found", "No request with this key was received")));
    }

    /** Test hook: every request fails with 503 for the given time (0 ends an outage). */
    @PostMapping("/test/outage")
    public ResponseEntity<Map<String, Object>> outage(@RequestParam @Min(0) @Max(3600) int seconds) {
        if (seconds == 0) {
            faultInjector.endOutage();
        } else {
            faultInjector.startOutage(Duration.ofSeconds(seconds));
        }
        log.warn("Simulated outage set to {}s", seconds);
        return ResponseEntity.ok(Map.of("outageSeconds", seconds));
    }

    /** @param fresh true when this request was processed now (not a replay, not an injected fault) */
    private record Outcome(ResponseEntity<String> response, boolean fresh) {
    }

    private ResponseEntity<String> run(String key, OperationType type, String hash, String paymentMethod,
                                       Supplier<ProcessorResult> operation) {
        return execute(key, type, hash, paymentMethod, operation).response();
    }

    private Outcome execute(String key, OperationType type, String hash, String paymentMethod,
                            Supplier<ProcessorResult> operation) {
        Optional<String> fault = faultInjector.failure(key, paymentMethod);
        if (fault.isPresent()) {
            count(type, 503, false);
            return new Outcome(json(ProcessorResult.error(503, "processor_unavailable", fault.get())), false);
        }
        IdempotentExecutor.Response response = executor.execute(key, type, hash, operation);
        count(type, response.status(), response.replayed());
        return new Outcome(ResponseEntity.status(response.status())
                .header("Idempotent-Replayed", String.valueOf(response.replayed()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.body()), !response.replayed());
    }

    private String paymentMethodOf(String authorizationId) {
        return processorService.paymentMethodOf(authorizationId).orElse(null);
    }

    private ResponseEntity<String> json(ProcessorResult result) {
        try {
            return ResponseEntity.status(result.status()).contentType(MediaType.APPLICATION_JSON)
                    .body(objectMapper.writeValueAsString(result.body()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private void count(OperationType type, int status, boolean replayed) {
        Counter.builder("processor.requests")
                .tag("operation", type.name())
                .tag("status", String.valueOf(status))
                .tag("replayed", String.valueOf(replayed))
                .register(meterRegistry)
                .increment();
    }

    /** Amounts compare by value, so 10.5 and 10.50 are the same request. */
    private static String money(BigDecimal amount) {
        return amount.stripTrailingZeros().toPlainString();
    }

    private static String hash(String... parts) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(String.join("|", java.util.Arrays.stream(parts).map(String::valueOf).toList())
                            .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
