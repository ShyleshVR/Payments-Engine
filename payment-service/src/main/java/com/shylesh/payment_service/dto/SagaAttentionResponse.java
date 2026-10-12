package com.shylesh.payment_service.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The sagas someone should look at: parked ones (REQUIRES_ATTENTION, waiting for an operator's
 * retry) and running ones that have been in the same step for too long. The counts are of all
 * such sagas; the list holds the oldest of each kind, up to the limit asked for.
 *
 * @param stuckAfterMinutes a running saga counts as stuck once its step is older than this
 */
public record SagaAttentionResponse(long parked, long stuck, int stuckAfterMinutes, List<Item> sagas) {

    /**
     * @param reason        PARKED or STUCK
     * @param stuckState    for a parked saga: the step it stopped in, which a retry resumes
     * @param minutesInStep how long it has been parked, or in its current step
     */
    public record Item(String paymentId, UUID sagaId, String type, String reason, String state, String stuckState,
                       int attempt, LocalDateTime stepStartedAt, long minutesInStep, String failureCode,
                       String lastError, LocalDateTime createdAt) {
    }
}
