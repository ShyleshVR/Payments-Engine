package com.shylesh.ledger_service.persistence;

public enum LedgerAccountType {

    PLATFORM_CLEARING,
    MERCHANT,
    /** Per merchant: refund amounts held while the processor refund is pending. */
    MERCHANT_REFUND_RESERVE,
    /** Per merchant: payout amounts held while the bank transfer is in progress. */
    MERCHANT_PAYOUT_RESERVE,
    /** Platform: money paid out to merchants' banks (a returned transfer comes back from it). */
    PAYOUT_CLEARING

}
