package com.shylesh.ledger_service.persistence;

public enum LedgerAccountType {

    PLATFORM_CLEARING,
    MERCHANT,
    /** Per merchant: refund amounts held while the processor refund is pending. */
    MERCHANT_REFUND_RESERVE

}
