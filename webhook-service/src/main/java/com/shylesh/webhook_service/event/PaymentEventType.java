package com.shylesh.webhook_service.event;

import java.util.Optional;

/** Event types delivered to merchants. Anything else is acknowledged and skipped. */
public enum PaymentEventType {

    PAYMENT_CREATED,
    PAYMENT_COMPLETED,
    PAYMENT_FAILED,
    PAYMENT_REFUNDED;

    public static Optional<PaymentEventType> parse(String eventType) {
        try {
            return Optional.of(valueOf(eventType));
        } catch (IllegalArgumentException | NullPointerException e) {
            return Optional.empty();
        }
    }
}
