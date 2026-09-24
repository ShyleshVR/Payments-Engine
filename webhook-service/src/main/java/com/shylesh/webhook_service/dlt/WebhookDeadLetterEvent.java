package com.shylesh.webhook_service.dlt;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Published when a webhook delivery exhausts its retries. Carries eventId so the whole
 * lifecycle — payment-service outbox event -> Kafka -> webhook delivery -> attempts -> this
 * dead letter — stays traceable by that one id, same as notification-service.
 */
@Getter
@AllArgsConstructor
public class WebhookDeadLetterEvent {

    private UUID eventId;
    private UUID deliveryId;
    private UUID paymentId;
    private UUID merchantId;
    private String eventType;
    private String url;
    private int attemptCount;
    private String lastError;
    private LocalDateTime failedAt;
}
