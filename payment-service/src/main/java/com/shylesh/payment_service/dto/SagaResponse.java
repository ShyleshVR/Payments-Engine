package com.shylesh.payment_service.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Operator view of a saga: where it is, why it stopped, and every step outcome so far. */
public record SagaResponse(
        UUID sagaId,
        String type,
        String state,
        String stuckState,
        int attempt,
        LocalDateTime nextAttemptAt,
        LocalDateTime stepStartedAt,
        String failureCode,
        String lastError,
        LocalDateTime createdAt,
        LocalDateTime finishedAt,
        List<Step> steps
) {

    public record Step(String state, String outcome, String detail, LocalDateTime occurredAt) {
    }
}
