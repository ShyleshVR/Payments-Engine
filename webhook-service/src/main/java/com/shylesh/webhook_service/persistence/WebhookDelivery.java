package com.shylesh.webhook_service.persistence;

import com.shylesh.webhook_service.exception.InvalidWebhookDeliveryStateException;

import jakarta.persistence.*;
import lombok.*;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One webhook to deliver to one merchant for one payment event. The url and rendered
 * payload are captured at creation time, so retries resend exactly what the first attempt
 * sent even if the merchant later changes their subscription.
 */
@Entity
@Table(name = "webhook_deliveries")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class WebhookDelivery {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "event_type", nullable = false, updatable = false)
    private String eventType;

    @Column(name = "payment_id", nullable = false, updatable = false)
    private UUID paymentId;

    @Column(name = "merchant_id", nullable = false, updatable = false)
    private UUID merchantId;

    @Column(name = "subscription_id", nullable = false, updatable = false)
    private UUID subscriptionId;

    @Column(nullable = false, length = 2048, updatable = false)
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

    @Column(name = "last_error", length = 1000)
    private String lastError;

    /**
     * When the dead letter for this FAILED delivery was confirmed by Kafka. Null on a FAILED
     * delivery means the dead letter is still pending; see WebhookDeadLetterRelay.
     */
    @Column(name = "dlt_published_at")
    private LocalDateTime dltPublishedAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

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
        this.lastError = error;
    }

    public void markFailed(int attemptCount, String error) {
        ensureDeliverable();
        this.status = WebhookDeliveryStatus.FAILED;
        this.attemptCount = attemptCount;
        this.nextAttemptAt = null;
        this.lastError = error;
    }

    public void markCancelled(String reason) {
        ensureDeliverable();
        this.status = WebhookDeliveryStatus.CANCELLED;
        this.nextAttemptAt = null;
        this.lastError = reason;
    }

    private void ensureDeliverable() {
        if (status != WebhookDeliveryStatus.PENDING && status != WebhookDeliveryStatus.RETRYING) {
            throw new InvalidWebhookDeliveryStateException(id, status);
        }
    }
}
