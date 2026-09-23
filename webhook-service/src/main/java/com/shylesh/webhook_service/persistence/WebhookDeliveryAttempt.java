package com.shylesh.webhook_service.persistence;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "webhook_delivery_attempts")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WebhookDeliveryAttempt {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "delivery_id", nullable = false, updatable = false)
    private UUID deliveryId;

    @Column(name = "attempt_number", nullable = false, updatable = false)
    private int attemptNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private DeliveryAttemptStatus status;

    /** HTTP status returned by the merchant; null when no response was received (timeout, DNS, refused). */
    @Column(name = "response_code", updatable = false)
    private Integer responseCode;

    @Column(name = "error_message", length = 1000, updatable = false)
    private String errorMessage;

    @Column(name = "attempted_at", nullable = false, updatable = false)
    private LocalDateTime attemptedAt;
}
