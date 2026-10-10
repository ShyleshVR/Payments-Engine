package com.shylesh.payout_service.saga;

/** The ledger commands of the payout saga (the ledger replies on payout-ledger-replies). */
public enum LedgerCommandType {

    HOLD_PAYOUT,
    RELEASE_PAYOUT,
    FINALIZE_PAYOUT,
    RETURN_PAYOUT

}
