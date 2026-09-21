package com.shylesh.notification_service.persistance;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "notification_delivery_attempts")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificationDeliveryAttempt {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "notification_id", nullable = false, updatable = false)
    private UUID notificationId;

    @Column(name = "attempt_number", nullable = false, updatable = false)
    private int attemptNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private DeliveryAttemptStatus status;

    @Column(name = "error_message", length = 1000, updatable = false)
    private String errorMessage;

    @Column(name = "attempted_at", nullable = false, updatable = false)
    private LocalDateTime attemptedAt;
}
