package com.shylesh.processor_simulator.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shylesh.processor_simulator.persistence.*;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The card processor's rules. Each method runs inside IdempotentExecutor's transaction, so its
 * effects and the stored response commit together.
 */
@Service
@RequiredArgsConstructor
public class ProcessorService {

    private final AuthorizationRepository authorizationRepository;
    private final RefundRepository refundRepository;
    private final OperationRepository operationRepository;
    private final ObjectMapper objectMapper;

    public ProcessorResult authorize(String paymentMethod, BigDecimal amount, String currency, String reference) {
        Optional<PaymentMethodBehaviour> behaviour = PaymentMethodBehaviour.of(paymentMethod);
        if (behaviour.isEmpty()) {
            return ProcessorResult.error(422, "invalid_payment_method", "Unknown payment method " + paymentMethod);
        }
        Optional<String> decline = behaviour.get().authorizationDeclineCode();
        if (decline.isPresent()) {
            return ProcessorResult.declined(decline.get(), "The card issuer declined the authorization", null);
        }

        LocalDateTime now = LocalDateTime.now();
        Authorization authorization = authorizationRepository.save(Authorization.builder()
                .id("auth_" + compactUuid())
                .paymentMethod(paymentMethod)
                .amount(amount)
                .currency(currency)
                .status(AuthorizationStatus.AUTHORIZED)
                .capturedAmount(BigDecimal.ZERO)
                .refundedAmount(BigDecimal.ZERO)
                .reference(reference)
                .createdAt(now)
                .updatedAt(now)
                .build());
        return ProcessorResult.of(201, body(authorization), authorization.getId());
    }

    public ProcessorResult capture(String authorizationId) {
        Optional<Authorization> found = authorizationRepository.findByIdForUpdate(authorizationId);
        if (found.isEmpty()) {
            return notFound(authorizationId);
        }
        Authorization authorization = found.get();
        switch (authorization.getStatus()) {
            case CAPTURED -> {
                return ProcessorResult.error(409, "already_captured", "Authorization is already captured");
            }
            case VOIDED -> {
                return ProcessorResult.error(409, "authorization_voided", "Authorization was voided");
            }
            case AUTHORIZED -> {
                Optional<String> decline = behaviourOf(authorization).flatMap(PaymentMethodBehaviour::captureDeclineCode);
                if (decline.isPresent()) {
                    return ProcessorResult.declined(decline.get(), "The capture was declined; the authorization is still open", authorization.getId());
                }
                authorization.capture(LocalDateTime.now());
                return ProcessorResult.of(200, body(authorization), authorization.getId());
            }
            default -> throw new IllegalStateException("Unexpected status " + authorization.getStatus());
        }
    }

    /** Releases the reserved funds. Voiding an already voided authorization succeeds again. */
    public ProcessorResult voidAuthorization(String authorizationId) {
        Optional<Authorization> found = authorizationRepository.findByIdForUpdate(authorizationId);
        if (found.isEmpty()) {
            return notFound(authorizationId);
        }
        Authorization authorization = found.get();
        if (authorization.getStatus() == AuthorizationStatus.CAPTURED) {
            return ProcessorResult.error(409, "already_captured", "A captured authorization can't be voided; refund it instead");
        }
        if (authorization.getStatus() == AuthorizationStatus.AUTHORIZED) {
            authorization.voidAuthorization(LocalDateTime.now());
        }
        return ProcessorResult.of(200, body(authorization), authorization.getId());
    }

    public ProcessorResult refund(String authorizationId, BigDecimal amount) {
        Optional<Authorization> found = authorizationRepository.findByIdForUpdate(authorizationId);
        if (found.isEmpty()) {
            return notFound(authorizationId);
        }
        Authorization authorization = found.get();
        if (authorization.getStatus() != AuthorizationStatus.CAPTURED) {
            return ProcessorResult.error(409, "not_captured", "Only captured payments can be refunded");
        }
        if (amount.compareTo(authorization.refundable()) > 0) {
            return ProcessorResult.error(422, "amount_exceeds_refundable",
                    "Refund exceeds the refundable amount " + authorization.refundable().toPlainString());
        }
        Optional<String> decline = behaviourOf(authorization).flatMap(PaymentMethodBehaviour::refundDeclineCode);
        if (decline.isPresent()) {
            return ProcessorResult.declined(decline.get(), "The refund was declined", authorization.getId());
        }

        LocalDateTime now = LocalDateTime.now();
        Refund refund = refundRepository.save(Refund.builder()
                .id("re_" + compactUuid())
                .authorizationId(authorization.getId())
                .amount(amount)
                .createdAt(now)
                .build());
        authorization.addRefund(amount, now);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", refund.getId());
        body.put("authorizationId", authorization.getId());
        body.put("amount", refund.getAmount());
        body.put("currency", authorization.getCurrency());
        body.put("status", "SUCCEEDED");
        return ProcessorResult.of(201, body, authorization.getId());
    }

    /**
     * Cancels an authorization request identified by the Idempotency-Key it was (or will be) sent
     * with, for a caller that never learned its outcome. If it was processed and approved, the
     * authorization is voided. If it hasn't arrived yet, a marker makes it fail when it does, so
     * a request still in flight can't reserve funds after the caller gave up on it.
     */
    public ProcessorResult reverse(String originalKey) {
        operationRepository.insertIfAbsent(originalKey, OperationType.AUTHORIZE.name(), null, true, LocalDateTime.now());
        Operation original = operationRepository.findByKeyForUpdate(originalKey).orElseThrow();
        if (original.getOperationType() != OperationType.AUTHORIZE) {
            return ProcessorResult.error(422, "not_an_authorization", "Only authorization requests can be reversed");
        }
        original.markReversed();
        operationRepository.save(original);

        if (!original.isCompleted() || original.getAuthorizationId() == null) {
            // never processed, or declined: nothing was reserved
            return reversed(null);
        }
        ProcessorResult voided = voidAuthorization(original.getAuthorizationId());
        if (voided.status() != 200) {
            return voided;
        }
        return reversed(original.getAuthorizationId());
    }

    /** What happened to a request, by its Idempotency-Key. */
    @Transactional(readOnly = true)
    public Optional<Map<String, Object>> inquiry(String key) {
        return operationRepository.findById(key).map(operation -> {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("idempotencyKey", operation.getIdempotencyKey());
            body.put("type", operation.getOperationType());
            body.put("state", operation.isCompleted() ? "COMPLETED" : "REVERSED_BEFORE_PROCESSING");
            body.put("reversed", operation.isReversed());
            body.put("responseStatus", operation.getResponseStatus());
            body.put("response", parse(operation.getResponseBody()));
            return body;
        });
    }

    @Transactional(readOnly = true)
    public Optional<String> paymentMethodOf(String authorizationId) {
        return authorizationRepository.findById(authorizationId).map(Authorization::getPaymentMethod);
    }

    private ProcessorResult reversed(String authorizationId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "REVERSED");
        body.put("authorizationId", authorizationId);
        return ProcessorResult.of(200, body, authorizationId);
    }

    private ProcessorResult notFound(String authorizationId) {
        return ProcessorResult.error(404, "authorization_not_found", "No authorization " + authorizationId);
    }

    private Optional<PaymentMethodBehaviour> behaviourOf(Authorization authorization) {
        return PaymentMethodBehaviour.of(authorization.getPaymentMethod());
    }

    private Map<String, Object> body(Authorization authorization) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", authorization.getId());
        body.put("status", authorization.getStatus());
        body.put("amount", authorization.getAmount());
        body.put("currency", authorization.getCurrency());
        body.put("paymentMethod", authorization.getPaymentMethod());
        body.put("capturedAmount", authorization.getCapturedAmount());
        body.put("refundedAmount", authorization.getRefundedAmount());
        return body;
    }

    private Object parse(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            return json;
        }
    }

    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
