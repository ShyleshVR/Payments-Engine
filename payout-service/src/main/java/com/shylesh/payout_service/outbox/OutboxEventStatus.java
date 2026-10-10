package com.shylesh.payout_service.outbox;

public enum OutboxEventStatus {

    PENDING,
    PUBLISHED,
    /**
     * Parked: the message can never be published as-is. It still blocks later messages for the
     * same payout, so ordering is never violated; it needs an operator to fix or discard it.
     */
    FAILED

}
