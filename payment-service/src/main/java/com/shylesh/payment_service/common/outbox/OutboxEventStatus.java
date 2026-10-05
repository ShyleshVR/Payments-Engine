package com.shylesh.payment_service.common.outbox;

public enum OutboxEventStatus {
    PENDING,
    PUBLISHED,
    /**
     * Parked: the event can never be published as-is (e.g. its payload can't be serialized).
     * It still blocks later events for the same aggregate, so ordering is never violated;
     * it needs an operator to fix or discard it.
     */
    FAILED
}
