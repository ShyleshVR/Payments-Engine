package com.shylesh.payment_service.saga;

import com.shylesh.payment_service.processor.ProcessorResponse;

/** Something that happened to a saga, for the state machine to decide on. */
public sealed interface SagaInput {

    /** The answer to the current step's processor call. */
    record ProcessorAnswer(ProcessorResponse response) implements SagaInput {
    }

    /** The ledger's reply to the command the saga is waiting for. */
    record LedgerAnswer(String outcome, String reason) implements SagaInput {
    }

    /** The saga came due without an answer: a ledger reply is overdue, or a MANUAL authorization expired. */
    record TimerFired() implements SagaInput {
    }

    record CaptureRequested() implements SagaInput {
    }

    record CancelRequested() implements SagaInput {
    }
}
