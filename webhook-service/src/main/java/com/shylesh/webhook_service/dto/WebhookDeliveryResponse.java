package com.shylesh.webhook_service.dto;

import com.shylesh.webhook_service.persistence.WebhookDelivery;
import com.shylesh.webhook_service.persistence.WebhookDeliveryStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** paymentId is in the public "pay_" form, matching the payment API and webhook payloads. */
public record WebhookDeliveryResponse(
        UUID deliveryId,
        UUID eventId,
        String eventType,
        String paymentId,
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
                "pay_" + delivery.getPaymentId(),
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
