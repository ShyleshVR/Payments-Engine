package com.shylesh.webhook_service.event;

import java.util.Optional;

/** Payout event types delivered to merchants. Anything else is acknowledged and skipped. */
public enum PayoutEventType {

    PAYOUT_CREATED,
    /** The money arrived at the merchant's bank (it can still come back: PAYOUT_RETURNED). */
    PAYOUT_PAID,
    /** Never paid (the bank rejected or failed the transfer); the amount is back in the balance. */
    PAYOUT_FAILED,
    /** Paid, then sent back by the merchant's bank; the amount is back in the balance. */
    PAYOUT_RETURNED;

    public static Optional<PayoutEventType> parse(String eventType) {
        try {
            return Optional.of(valueOf(eventType));
        } catch (IllegalArgumentException | NullPointerException e) {
            return Optional.empty();
        }
    }
}
