package com.shylesh.ledger_service.outbox;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/** A message to publish, written in the same transaction as the ledger change it reports. */
@Entity
@Table(name = "outbox_event")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OutboxEvent {

    public static final int MAX_ERROR_LENGTH = 1000;

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    /** Kafka key and ordering scope: the payment id. */
    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private UUID aggregateId;

    @Column(nullable = false, updatable = false, length = 100)
    private String topic;

    @Column(name = "event_type", nullable = false, updatable = false, length = 100)
    private String eventType;

    /** The complete message, sent as-is. */
    @Column(nullable = false, columnDefinition = "TEXT", updatable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OutboxEventStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "seq", insertable = false, updatable = false)
    private Long seq;

    @Column(name = "attempt_count", nullable = false)
    @Builder.Default
    private int attemptCount = 0;

    @Column(name = "next_attempt_at")
    private LocalDateTime nextAttemptAt;

    @Column(name = "last_error", length = MAX_ERROR_LENGTH)
    private String lastError;

    /** Trace of the command that produced this reply; the relay sends within it. */
    @Column(name = "trace_parent", updatable = false, length = 100)
    private String traceParent;

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
