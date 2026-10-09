package com.shylesh.webhook_service.event;

import java.util.Optional;

/** Event types delivered to merchants. Anything else is acknowledged and skipped. */
public enum PaymentEventType {

    PAYMENT_CREATED,
    /** MANUAL capture: funds reserved, waiting for the merchant to capture or cancel. */
    PAYMENT_AUTHORIZED,
    PAYMENT_COMPLETED,
    PAYMENT_FAILED,
    /** Authorization released (merchant cancel or expiry); nothing was charged. */
    PAYMENT_CANCELLED,
    PAYMENT_REFUNDED,
    /** A refund did not happen (insufficient merchant balance, or refused by the processor). */
    PAYMENT_REFUND_FAILED;

    public static Optional<PaymentEventType> parse(String eventType) {
        try {
            return Optional.of(valueOf(eventType));
        } catch (IllegalArgumentException | NullPointerException e) {
            return Optional.empty();
        }
    }
}
