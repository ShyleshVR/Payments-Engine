package com.shylesh.payment_service.common.outbox;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "outbox_event")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OutboxEvent {

    /**
     * Unique identifier of this event.
     *
     * This ID is propagated as eventId to Kafka consumers.
     */
    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private UUID aggregateId;

    @Column(name = "aggregate_type", nullable = false, updatable = false)
    private String aggregateType;

    @Column(name = "event_type", nullable = false, updatable = false)
    private String eventType;

    @Column(nullable = false, columnDefinition = "TEXT", updatable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OutboxEventStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    /** Database-assigned insertion order; used to publish each aggregate's events in order. */
    @Column(name = "seq", insertable = false, updatable = false)
    private Long seq;

    @Column(name = "attempt_count", nullable = false)
    @Builder.Default
    private int attemptCount = 0;

    @Column(name = "next_attempt_at")
    private LocalDateTime nextAttemptAt;

    @Column(name = "last_error", length = MAX_ERROR_LENGTH)
    private String lastError;

    public static final int MAX_ERROR_LENGTH = 1000;

    public void markPublished(LocalDateTime publishedAt) {
        this.status = OutboxEventStatus.PUBLISHED;
        this.publishedAt = publishedAt;
        this.attemptCount++;
        this.nextAttemptAt = null;
        this.lastError = null;
    }

    public void scheduleRetry(LocalDateTime nextAttemptAt, String error) {
        this.attemptCount++;
        this.nextAttemptAt = nextAttemptAt;
        this.lastError = truncate(error);
    }

    public void markFailed(String error) {
        this.status = OutboxEventStatus.FAILED;
        this.attemptCount++;
        this.nextAttemptAt = null;
        this.lastError = truncate(error);
    }

    private static String truncate(String error) {
        if (error == null || error.length() <= MAX_ERROR_LENGTH) {
            return error;
        }
        return error.substring(0, MAX_ERROR_LENGTH);
    }
}