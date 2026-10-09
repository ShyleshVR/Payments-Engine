package com.shylesh.payment_service.saga;

/** Commands to ledger-service (see ledger-service's LedgerCommandType). */
public enum LedgerCommandType {

    SETTLE_PAYMENT,
    HOLD_REFUND,
    RELEASE_HOLD,
    FINALIZE_REFUND

}
