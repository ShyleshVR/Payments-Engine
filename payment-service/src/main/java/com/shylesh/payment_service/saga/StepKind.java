package com.shylesh.payment_service.saga;

/** What the worker does when a saga in a given state is due. */
public enum StepKind {

    /** Call the card processor (HTTP, outside any DB transaction). */
    PROCESSOR_CALL,
    /** Re-send the ledger command whose reply is overdue. */
    LEDGER_COMMAND,
    /** Waiting for the merchant; due only when the authorization expires. */
    WAIT_FOR_MERCHANT,
    /** Nothing: finished, or parked for an operator. */
    NONE

}
