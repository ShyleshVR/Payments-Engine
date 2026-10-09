package com.shylesh.ledger_service.command;

/** What payment-service's saga orchestrator can ask the ledger to do. */
public enum LedgerCommandType {

    /** Captured payment: debit platform clearing, credit the merchant. */
    SETTLE_PAYMENT,
    /** Refund step 1: move the amount from the merchant's balance into their refund reserve, if it covers it. */
    HOLD_REFUND,
    /** Refund compensation: the processor refused the refund, move the reserved amount back. */
    RELEASE_HOLD,
    /** Refund done at the processor: the reserved amount leaves through platform clearing. */
    FINALIZE_REFUND

}
