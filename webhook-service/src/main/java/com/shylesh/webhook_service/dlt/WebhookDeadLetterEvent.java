package com.shylesh.webhook_service.dlt;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Published when a delivery fails for good. Carries eventId so the whole path (payment
 * outbox -> Kafka -> delivery -> attempts -> dead letter) stays traceable by one id.
 */
public record WebhookDeadLetterEvent(
        UUID eventId,
        UUID deliveryId,
        UUID paymentId,
        UUID payoutId,
        UUID merchantId,
        String eventType,
        String url,
        int attemptCount,
        String lastError,
        LocalDateTime failedAt
) {
}
