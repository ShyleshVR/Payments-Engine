package com.shylesh.ledger_service.persistence;

public enum LedgerTransactionType {

    SETTLEMENT,
    /** Refund amount moved from the merchant's balance into their refund reserve. */
    REFUND_HOLD,
    /** Compensation: a held refund amount returned to the merchant's balance. */
    REFUND_HOLD_RELEASE,
    /** A refund paid out: the reserved amount leaves through platform clearing. */
    REFUND

}
