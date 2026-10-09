package com.shylesh.reconciliation_service.domain;

/** Ways the processor, the ledger and payment-service can disagree about a payment. */
public enum DiscrepancyType {

    /** The processor charged the customer; the ledger never booked it. */
    CAPTURED_NOT_SETTLED,
    /** The ledger booked money the processor never charged. */
    SETTLED_NOT_CAPTURED,
    /** Both exist but the amounts (or currencies) differ. */
    SETTLEMENT_AMOUNT_MISMATCH,
    /** The processor refunded; the ledger has no refund posting. */
    REFUNDED_NOT_BOOKED,
    /** The ledger posted a refund the processor never made. */
    BOOKED_NOT_REFUNDED,
    /** Refunds on both sides, different totals. */
    REFUND_AMOUNT_MISMATCH,
    /** The payment's status contradicts what money actually did. */
    STATUS_MISMATCH,
    /** The payment failed or was cancelled, but the processor still holds the customer's funds. */
    AUTHORIZATION_NOT_RELEASED,
    /** A refund hold in the ledger was neither released nor completed. */
    REFUND_HOLD_STALE,
    /** More than one capture, settlement or refund posting for one payment. */
    DUPLICATE,
    /** Money moved for a payment id payment-service doesn't know. */
    UNKNOWN_PAYMENT

}
