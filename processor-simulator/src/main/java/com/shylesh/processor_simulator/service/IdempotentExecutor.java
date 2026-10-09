package com.shylesh.processor_simulator.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shylesh.processor_simulator.persistence.Operation;
import com.shylesh.processor_simulator.persistence.OperationRepository;
import com.shylesh.processor_simulator.persistence.OperationType;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Runs an operation at most once per Idempotency-Key and replays its response for every retry.
 * The key's row is locked for the whole transaction, and the operation's effects and its stored
 * response commit together: a caller that timed out can always retry with the same key and gets
 * either the original answer or (if nothing was committed) a fresh attempt, never a second effect.
 */
@Component
@RequiredArgsConstructor
public class IdempotentExecutor {

    private final OperationRepository operationRepository;
    private final ObjectMapper objectMapper;

    /** @param replayed true when the stored response of an earlier request is returned */
    public record Response(int status, String body, boolean replayed) {
    }

    @Transactional
    public Response execute(String key, OperationType type, String requestHash, Supplier<ProcessorResult> operation) {
        LocalDateTime now = LocalDateTime.now();
        operationRepository.insertIfAbsent(key, type.name(), requestHash, false, now);
        Operation record = operationRepository.findByKeyForUpdate(key).orElseThrow();

        if (record.isReversedBeforeProcessing()) {
            if (type == OperationType.AUTHORIZE) {
                return fresh(ProcessorResult.error(409, "reversed",
                        "This authorization request was reversed before it was processed"));
            }
            return keyReused();
        }
        if (record.getOperationType() != type || !Objects.equals(record.getRequestHash(), requestHash)) {
            return keyReused();
        }
        if (record.isCompleted()) {
            return new Response(record.getResponseStatus(), record.getResponseBody(), true);
        }

        ProcessorResult result = operation.get();
        String body = toJson(result);
        record.complete(result.status(), body, result.authorizationId(), now);
        operationRepository.save(record);
        return new Response(result.status(), body, false);
    }

    private Response keyReused() {
        return fresh(ProcessorResult.error(422, "idempotency_key_reused",
                "This Idempotency-Key was already used for a different request"));
    }

    private Response fresh(ProcessorResult result) {
        return new Response(result.status(), toJson(result), false);
    }

    private String toJson(ProcessorResult result) {
        try {
            return objectMapper.writeValueAsString(result.body());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unserializable processor response", e);
        }
    }
}
