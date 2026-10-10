package com.shylesh.webhook_service.persistence;

import com.shylesh.webhook_service.exception.InvalidWebhookDeliveryStateException;

import jakarta.persistence.*;
import lombok.*;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "webhook_deliveries")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class WebhookDelivery {

    public static final int MAX_ERROR_LENGTH = 1000;

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "event_type", nullable = false, updatable = false)
    private String eventType;

    /** The payment the event is about (null for a payout event). */
    @Column(name = "payment_id", updatable = false)
    private UUID paymentId;

    /** The payout the event is about (null for a payment event). */
    @Column(name = "payout_id", updatable = false)
    private UUID payoutId;

    @Column(name = "merchant_id", nullable = false, updatable = false)
    private UUID merchantId;

    @Column(name = "subscription_id", nullable = false, updatable = false)
    private UUID subscriptionId;

    @Column(nullable = false, updatable = false, length = 2048)
    private String url;

    @Column(nullable = false, updatable = false, columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WebhookDeliveryStatus status;

    @Column(name = "attempt_count", nullable = false)
    @Builder.Default
    private int attemptCount = 0;

    @Column(name = "next_attempt_at")
    private LocalDateTime nextAttemptAt;

    @Column(name = "last_error", length = MAX_ERROR_LENGTH)
    private String lastError;

    @Column(name = "dlt_published_at")
    private LocalDateTime dltPublishedAt;

    @Version
    @Column(nullable = false)
    private Long version;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * Hides the delivery from other dispatchers until leaseUntil while the HTTP request runs
     * outside any transaction. If the sender dies mid-request, the lease expires and the
     * delivery is picked up again.
     */
    public void lease(LocalDateTime leaseUntil) {
        ensureDeliverable();
        this.nextAttemptAt = leaseUntil;
    }

    public void markDelivered(int attemptCount) {
        ensureDeliverable();
        this.status = WebhookDeliveryStatus.DELIVERED;
        this.attemptCount = attemptCount;
        this.nextAttemptAt = null;
        this.lastError = null;
    }

    public void markRetrying(int attemptCount, LocalDateTime nextAttemptAt, String error) {
        ensureDeliverable();
        this.status = WebhookDeliveryStatus.RETRYING;
        this.attemptCount = attemptCount;
        this.nextAttemptAt = nextAttemptAt;
        this.lastError = truncateError(error);
    }

    public void markFailed(int attemptCount, String error) {
        ensureDeliverable();
        this.status = WebhookDeliveryStatus.FAILED;
        this.attemptCount = attemptCount;
        this.nextAttemptAt = null;
        this.lastError = truncateError(error);
    }

    public void markCancelled(String reason) {
        ensureDeliverable();
        this.status = WebhookDeliveryStatus.CANCELLED;
        this.nextAttemptAt = null;
        this.lastError = truncateError(reason);
    }

    public void markDeadLetterPublished(LocalDateTime publishedAt) {
        if (status != WebhookDeliveryStatus.FAILED) {
            throw new InvalidWebhookDeliveryStateException(id, status);
        }
        this.dltPublishedAt = publishedAt;
    }

    public boolean isDeliverable() {
        return status == WebhookDeliveryStatus.PENDING || status == WebhookDeliveryStatus.RETRYING;
    }

    public static String truncateError(String error) {
        if (error == null || error.length() <= MAX_ERROR_LENGTH) {
            return error;
        }
        return error.substring(0, MAX_ERROR_LENGTH);
    }

    private void ensureDeliverable() {
        if (!isDeliverable()) {
            throw new InvalidWebhookDeliveryStateException(id, status);
        }
    }
}
