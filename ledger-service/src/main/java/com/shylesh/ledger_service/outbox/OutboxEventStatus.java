package com.shylesh.ledger_service.outbox;

public enum OutboxEventStatus {

    PENDING,
    PUBLISHED,
    /**
     * Parked: the message can never be published as-is. It still blocks later messages for the
     * same payment, so ordering is never violated; it needs an operator to fix or discard it.
     */
    FAILED

}
