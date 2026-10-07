package com.shylesh.webhook_service.exception;

import com.shylesh.webhook_service.persistence.WebhookDeliveryStatus;

import java.util.UUID;

public class InvalidWebhookDeliveryStateException extends RuntimeException {

    public InvalidWebhookDeliveryStateException(UUID deliveryId, WebhookDeliveryStatus current) {
        super("Cannot process webhook delivery " + deliveryId + " in status " + current);
    }
}
