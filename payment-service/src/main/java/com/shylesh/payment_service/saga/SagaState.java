package com.shylesh.payment_service.saga;

/**
 * Saga states. The pivot of the payment saga is CAPTURING: before it, a failure is compensated
 * by voiding the authorization; once the capture may have happened, money has moved and the
 * remaining steps are only ever retried, never compensated.
 */
public enum SagaState {

    // payment saga
    AUTHORIZING(SagaType.PAYMENT, StepKind.PROCESSOR_CALL, false),
    AWAITING_CAPTURE(SagaType.PAYMENT, StepKind.WAIT_FOR_MERCHANT, false),
    CAPTURING(SagaType.PAYMENT, StepKind.PROCESSOR_CALL, false),
    /** Compensation: release the authorization (reversal by the authorize request's key). */
    VOIDING(SagaType.PAYMENT, StepKind.PROCESSOR_CALL, false),
    SETTLING(SagaType.PAYMENT, StepKind.LEDGER_COMMAND, false),
    COMPLETED(SagaType.PAYMENT, StepKind.NONE, true),
    FAILED(SagaType.PAYMENT, StepKind.NONE, true),
    CANCELLED(SagaType.PAYMENT, StepKind.NONE, true),

    // refund saga
    HOLDING(SagaType.REFUND, StepKind.LEDGER_COMMAND, false),
    REFUNDING(SagaType.REFUND, StepKind.PROCESSOR_CALL, false),
    /** Compensation: the processor refused the refund, return the held amount. */
    RELEASING(SagaType.REFUND, StepKind.LEDGER_COMMAND, false),
    FINALIZING(SagaType.REFUND, StepKind.LEDGER_COMMAND, false),
    REFUNDED(SagaType.REFUND, StepKind.NONE, true),
    REFUND_FAILED(SagaType.REFUND, StepKind.NONE, true),

    /** Either saga: an outcome that must not be guessed at. Parked until an operator retries it. */
    REQUIRES_ATTENTION(null, StepKind.NONE, false);

    private final SagaType type;
    private final StepKind kind;
    private final boolean terminal;

    SagaState(SagaType type, StepKind kind, boolean terminal) {
        this.type = type;
        this.kind = kind;
        this.terminal = terminal;
    }

    public SagaType type() {
        return type;
    }

    public StepKind kind() {
        return kind;
    }

    public boolean isTerminal() {
        return terminal;
    }
}
