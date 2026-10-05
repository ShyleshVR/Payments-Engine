package com.shylesh.notification_service.persistance;

import jakarta.persistence.*;
import lombok.*;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.shylesh.notification_service.exception.InvalidNotificationStateException;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "notifications")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class Notification {

    public static final int MAX_ERROR_LENGTH = 1000;

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "event_type", nullable = false, updatable = false)
    private String eventType;

    @Column(name = "payment_id", nullable = false, updatable = false)
    private UUID paymentId;

    @Column(name = "customer_id", updatable = false)
    private UUID customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private NotificationChannelType channel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationStatus status;

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
     * Leases the notification to the caller until leaseUntil: pushing next_attempt_at forward
     * hides it from other dispatchers while the send runs outside any transaction. If the
     * caller dies mid-send, the lease simply expires and the notification is picked up again.
     */
    public void lease(LocalDateTime leaseUntil) {
        ensureDeliverable();
        this.nextAttemptAt = leaseUntil;
    }

    public void markSent(int attemptCount) {
        ensureDeliverable();
        this.status = NotificationStatus.SENT;
        this.attemptCount = attemptCount;
        this.nextAttemptAt = null;
        this.lastError = null;
    }

    public void markRetrying(int attemptCount, LocalDateTime nextAttemptAt, String error) {
        ensureDeliverable();
        this.status = NotificationStatus.RETRYING;
        this.attemptCount = attemptCount;
        this.nextAttemptAt = nextAttemptAt;
        this.lastError = truncateError(error);
    }

    public void markFailed(int attemptCount, String error) {
        ensureDeliverable();
        this.status = NotificationStatus.FAILED;
        this.attemptCount = attemptCount;
        this.nextAttemptAt = null;
        this.lastError = truncateError(error);
    }

    public void markDeadLetterPublished(LocalDateTime publishedAt) {
        if (status != NotificationStatus.FAILED) {
            throw new InvalidNotificationStateException(id, status);
        }
        this.dltPublishedAt = publishedAt;
    }

    public boolean isDeliverable() {
        return status == NotificationStatus.PENDING || status == NotificationStatus.RETRYING;
    }

    public static String truncateError(String error) {
        if (error == null || error.length() <= MAX_ERROR_LENGTH) {
            return error;
        }
        return error.substring(0, MAX_ERROR_LENGTH);
    }

    private void ensureDeliverable() {
        if (!isDeliverable()) {
            throw new InvalidNotificationStateException(id, status);
        }
    }
}
