package com.shylesh.payout_service.saga;

/**
 * Payout saga states. The pivot is SUBMITTING: until the bank has the transfer, a failure is
 * compensated by releasing the hold; once it may have it, money may be moving, so the saga only
 * ever goes forward (or stops for an operator). A paid payout is still watched for a while,
 * because the merchant's bank can send it back.
 */
public enum SagaState {

    /** The ledger moves the amount from the merchant's payable balance into their payout reserve. */
    HOLDING(StepKind.LEDGER_COMMAND, false),
    /** The transfer is sent to the bank (idempotency key derived from the saga). */
    SUBMITTING(StepKind.BANK_CALL, false),
    /** The bank accepted the transfer; checked until it is paid or fails. */
    IN_TRANSIT(StepKind.BANK_CALL, false),
    /** The bank paid it: the ledger moves the reserve out through payout clearing. */
    FINALIZING(StepKind.LEDGER_COMMAND, false),
    /** Compensation: the bank rejected or failed the transfer; the ledger returns the reserve. */
    RELEASING(StepKind.LEDGER_COMMAND, false),
    /** Paid; checked now and then for a return until the return window closes. */
    RETURN_WINDOW(StepKind.BANK_CALL, false),
    /** The merchant's bank sent it back: the ledger credits the amount back. */
    RETURNING(StepKind.LEDGER_COMMAND, false),

    /** Paid and the return window closed. */
    COMPLETED(StepKind.NONE, true),
    FAILED(StepKind.NONE, true),
    RETURNED(StepKind.NONE, true),

    /** An outcome that must not be guessed at. Parked until an operator retries it. */
    REQUIRES_ATTENTION(StepKind.NONE, false);

    private final StepKind kind;
    private final boolean terminal;

    SagaState(StepKind kind, boolean terminal) {
        this.kind = kind;
        this.terminal = terminal;
    }

    public StepKind kind() {
        return kind;
    }

    public boolean isTerminal() {
        return terminal;
    }
}
