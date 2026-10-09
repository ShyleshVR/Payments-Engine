package com.shylesh.payment_service.saga;

/** How a decision changes the payment (and which event it publishes). */
public enum PaymentChange {

    NONE,
    /** Authorized; MANUAL capture publishes PAYMENT_AUTHORIZED. */
    AUTHORIZED,
    CAPTURE_REQUESTED,
    /** PAYMENT_COMPLETED */
    SUCCEEDED,
    /** PAYMENT_FAILED */
    FAILED,
    /** PAYMENT_CANCELLED */
    CANCELLED,
    /** PAYMENT_REFUNDED */
    REFUNDED,
    /** PAYMENT_REFUND_FAILED */
    REFUND_FAILED

}
