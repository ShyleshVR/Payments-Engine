package com.shylesh.webhook_service.dto;

import com.shylesh.webhook_service.persistence.WebhookDelivery;
import com.shylesh.webhook_service.persistence.WebhookDeliveryStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * paymentId ("pay_") or payoutId ("po_"), in the public form of the payment and payout APIs and
 * their webhook payloads; the other one is null.
 */
public record WebhookDeliveryResponse(
        UUID deliveryId,
        UUID eventId,
        String eventType,
        String paymentId,
        String payoutId,
        UUID merchantId,
        String url,
        WebhookDeliveryStatus status,
        int attemptCount,
        LocalDateTime nextAttemptAt,
        String lastError,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        List<WebhookDeliveryAttemptResponse> attempts
) {

    public static WebhookDeliveryResponse of(WebhookDelivery delivery, List<WebhookDeliveryAttemptResponse> attempts) {
        return new WebhookDeliveryResponse(
                delivery.getId(),
                delivery.getEventId(),
                delivery.getEventType(),
                delivery.getPaymentId() == null ? null : "pay_" + delivery.getPaymentId(),
                delivery.getPayoutId() == null ? null : "po_" + delivery.getPayoutId(),
                delivery.getMerchantId(),
                delivery.getUrl(),
                delivery.getStatus(),
                delivery.getAttemptCount(),
                delivery.getNextAttemptAt(),
                delivery.getLastError(),
                delivery.getCreatedAt(),
                delivery.getUpdatedAt(),
                attempts
        );
    }
}
