package com.shylesh.notification_service.dlt;

import com.shylesh.notification_service.persistance.NotificationChannelType;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Published when a notification exhausts its delivery retries. Carries eventId so the
 * complete lifecycle — payment-service outbox event -> Kafka -> notification record ->
 * delivery attempts -> this dead letter — stays traceable by that one id.
 */
@Getter
@AllArgsConstructor
public class NotificationDeadLetterEvent {

    private UUID eventId;
    private UUID notificationId;
    private UUID paymentId;
    private UUID customerId;
    private String eventType;
    private NotificationChannelType channel;
    private int attemptCount;
    private String lastError;
    private LocalDateTime failedAt;
}
