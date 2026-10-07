package com.shylesh.webhook_service.persistence;

public enum WebhookDeliveryStatus {

    PENDING,
    RETRYING,
    DELIVERED,
    /** Retries exhausted, or the merchant answered with a permanent error (4xx). Dead-lettered. */
    FAILED,
    /** The subscription was deactivated before the delivery went out. Not dead-lettered. */
    CANCELLED

}
