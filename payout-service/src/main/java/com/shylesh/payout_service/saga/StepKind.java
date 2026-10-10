package com.shylesh.payout_service.saga;

/** What the worker does when a saga in a given state is due. */
public enum StepKind {

    /** Call the bank (HTTP, outside any DB transaction): send the transfer, or check on it. */
    BANK_CALL,
    /** Re-send the ledger command whose reply is overdue. */
    LEDGER_COMMAND,
    /** Nothing: finished, or parked for an operator. */
    NONE

}
