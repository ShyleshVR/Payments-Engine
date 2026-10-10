package com.shylesh.payout_service.saga;

import com.shylesh.payout_service.client.BankResponse;

/** Something that happened to a saga, for the state machine to decide on. */
public sealed interface SagaInput {

    /** The answer to the current step's bank call. */
    record BankAnswer(BankResponse response) implements SagaInput {
    }

    /** The ledger's reply to the command the saga is waiting for. */
    record LedgerAnswer(String outcome, String reason) implements SagaInput {
    }

    /** The saga came due without an answer: a ledger reply is overdue. */
    record TimerFired() implements SagaInput {
    }
}
