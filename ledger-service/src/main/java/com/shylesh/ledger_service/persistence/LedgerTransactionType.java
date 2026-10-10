package com.shylesh.ledger_service.persistence;

public enum LedgerTransactionType {

    SETTLEMENT,
    /** Refund amount moved from the merchant's balance into their refund reserve. */
    REFUND_HOLD,
    /** Compensation: a held refund amount returned to the merchant's balance. */
    REFUND_HOLD_RELEASE,
    /** A refund paid out: the reserved amount leaves through platform clearing. */
    REFUND,
    /** Payout amount moved from the merchant's balance into their payout reserve. */
    PAYOUT_HOLD,
    /** Compensation: the bank rejected or failed the transfer, the held amount goes back. */
    PAYOUT_HOLD_RELEASE,
    /** The bank paid the transfer: the reserved amount leaves through payout clearing. */
    PAYOUT,
    /** The merchant's bank returned a paid transfer: the amount is credited back. */
    PAYOUT_RETURN

}
