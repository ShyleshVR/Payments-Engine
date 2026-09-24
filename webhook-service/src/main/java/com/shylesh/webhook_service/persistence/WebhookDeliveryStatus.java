package com.shylesh.webhook_service.persistence;

public enum WebhookDeliveryStatus {

    PENDING,
    RETRYING,
    DELIVERED,
    FAILED,
    /** The subscription was deactivated before this delivery succeeded. */
    CANCELLED

}
