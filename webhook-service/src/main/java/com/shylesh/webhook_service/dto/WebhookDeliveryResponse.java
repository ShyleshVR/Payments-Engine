package com.shylesh.webhook_service.dto;

import com.shylesh.webhook_service.persistence.WebhookDelivery;
import com.shylesh.webhook_service.persistence.WebhookDeliveryStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record WebhookDeliveryResponse(
        UUID id,
        UUID eventId,
        String eventType,
        UUID paymentId,
        UUID merchantId,
        String url,
        WebhookDeliveryStatus status,
        int attemptCount,
        LocalDateTime nextAttemptAt,
        String lastError,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String payload,
        List<WebhookDeliveryAttemptResponse> attempts
) {

    public static WebhookDeliveryResponse from(WebhookDelivery delivery, List<WebhookDeliveryAttemptResponse> attempts) {
        return new WebhookDeliveryResponse(
                delivery.getId(),
                delivery.getEventId(),
                delivery.getEventType(),
                delivery.getPaymentId(),
                delivery.getMerchantId(),
                delivery.getUrl(),
                delivery.getStatus(),
                delivery.getAttemptCount(),
                delivery.getNextAttemptAt(),
                delivery.getLastError(),
                delivery.getCreatedAt(),
                delivery.getUpdatedAt(),
                delivery.getPayload(),
                attempts
        );
    }
}
