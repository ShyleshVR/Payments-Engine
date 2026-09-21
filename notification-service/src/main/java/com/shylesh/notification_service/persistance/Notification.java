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

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

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
        this.lastError = error;
    }

    public void markFailed(int attemptCount, String error) {
        ensureDeliverable();
        this.status = NotificationStatus.FAILED;
        this.attemptCount = attemptCount;
        this.nextAttemptAt = null;
        this.lastError = error;
    }

    private void ensureDeliverable() {
        if (status != NotificationStatus.PENDING && status != NotificationStatus.RETRYING) {
            throw new InvalidNotificationStateException(id, status);
        }
    }
}
