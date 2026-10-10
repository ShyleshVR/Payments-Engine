package com.shylesh.reconciliation_service.domain;

/**
 * Ways the processor (or bank), the ledger and payment-service (or payout-service) can disagree
 * about a payment or a payout.
 */
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
    UNKNOWN_PAYMENT,

    // payouts: the bank (what money did), the ledger, payout-service
    /** The bank paid the merchant; the ledger never booked the payout. */
    PAID_NOT_BOOKED,
    /** The ledger booked a payout the bank never paid. */
    BOOKED_NOT_PAID,
    /** Both exist; amounts or currencies differ. */
    PAYOUT_AMOUNT_MISMATCH,
    /** The merchant's bank sent a payout back; the ledger doesn't show it. */
    RETURN_NOT_BOOKED,
    /** The ledger booked a return the bank never made. */
    RETURN_BOOKED_NOT_RETURNED,
    /** The payout's status contradicts the money (e.g. FAILED but paid). */
    PAYOUT_STATUS_MISMATCH,
    /** A payout hold open far longer than a transfer takes. */
    PAYOUT_HOLD_STALE,
    /** Money moved for a payout payout-service doesn't know. */
    UNKNOWN_PAYOUT

}
