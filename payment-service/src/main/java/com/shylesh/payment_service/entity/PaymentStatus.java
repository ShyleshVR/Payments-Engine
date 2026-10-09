package com.shylesh.payment_service.entity;

/**
 * What a merchant sees. The saga (PaymentSaga) holds the step-level detail behind it.
 */
public enum PaymentStatus {

    /** Accepted before sagas existed; new payments start in PROCESSING. */
    CREATED,
    /** Being authorized, captured or settled. */
    PROCESSING,
    /** MANUAL capture: funds reserved on the card, waiting for the merchant to capture or cancel. */
    AUTHORIZED,
    /** Captured and booked to the merchant's balance. */
    SUCCESS,
    /** Declined, or could not be completed; failureCode says why. Nothing was charged. */
    FAILED,
    /** Authorization released (merchant cancel or expiry). Nothing was charged. */
    CANCELLED,
    /** A refund is in progress. */
    REFUND_PENDING,
    REFUNDED

}
