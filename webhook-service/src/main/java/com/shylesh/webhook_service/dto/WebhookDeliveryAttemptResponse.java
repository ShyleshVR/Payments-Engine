package com.shylesh.webhook_service.dto;

import com.shylesh.webhook_service.persistence.DeliveryAttemptStatus;
import com.shylesh.webhook_service.persistence.WebhookDeliveryAttempt;

import java.time.LocalDateTime;

public record WebhookDeliveryAttemptResponse(
        int attemptNumber,
        DeliveryAttemptStatus status,
        Integer responseCode,
        String errorMessage,
        LocalDateTime attemptedAt
) {

    public static WebhookDeliveryAttemptResponse from(WebhookDeliveryAttempt attempt) {
        return new WebhookDeliveryAttemptResponse(
                attempt.getAttemptNumber(),
                attempt.getStatus(),
                attempt.getResponseCode(),
                attempt.getErrorMessage(),
                attempt.getAttemptedAt()
        );
    }
}
