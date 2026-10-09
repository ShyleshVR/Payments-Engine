package com.shylesh.processor_simulator.persistence;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * The record behind one Idempotency-Key: the request it was first used for (type + hash) and
 * the response it produced, replayed for every retry with the same key.
 */
@Entity
@Table(name = "operations")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Operation {

    @Id
    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 100)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation_type", nullable = false, length = 20)
    private OperationType operationType;

    @Column(name = "request_hash", length = 64)
    private String requestHash;

    /** An authorization request reversed (cancelled by the caller) before or after it was processed. */
    @Column(nullable = false)
    private boolean reversed;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "response_body", columnDefinition = "TEXT")
    private String responseBody;

    @Column(name = "authorization_id", length = 40)
    private String authorizationId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    public boolean isCompleted() {
        return responseStatus != null;
    }

    /** A reversal arrived for this key before any request with it was processed. */
    public boolean isReversedBeforeProcessing() {
        return reversed && !isCompleted();
    }

    public void claim(OperationType type, String hash) {
        this.operationType = type;
        this.requestHash = hash;
    }

    public void complete(int status, String body, String authorizationId, LocalDateTime now) {
        this.responseStatus = status;
        this.responseBody = body;
        this.authorizationId = authorizationId;
        this.completedAt = now;
    }

    public void markReversed() {
        this.reversed = true;
    }
}
