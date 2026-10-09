package com.shylesh.payment_service.saga;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param pollInterval       how often the worker looks for due sagas
 * @param workers            processor calls in parallel per instance
 * @param maxInFlight        sagas queued or running per instance
 * @param lease              how long a claimed step is hidden from other workers; must outlast a
 *                           processor call (connect + read timeout), or a slow call could be
 *                           repeated by another worker (harmless, same key, but wasteful)
 * @param authorizationTtl   MANUAL capture: uncaptured authorizations are voided after this
 * @param stepTimeouts       how long a processor step may stay unanswered before giving up
 * @param retry              backoff between attempts of a processor step with an unknown outcome
 * @param replies            how long to wait for a ledger reply before re-sending the command
 */
@ConfigurationProperties(prefix = "payflow.saga")
public record SagaProperties(
        @DefaultValue("500ms") Duration pollInterval,
        @DefaultValue("8") int workers,
        @DefaultValue("100") int maxInFlight,
        @DefaultValue("30s") Duration lease,
        @DefaultValue("7d") Duration authorizationTtl,
        @DefaultValue StepTimeouts stepTimeouts,
        @DefaultValue Retry retry,
        @DefaultValue Replies replies
) {

    /**
     * @param authorize before the pivot: then the authorization is reversed and the payment fails
     * @param capture   at the pivot: then the saga is parked for an operator (never guessed)
     * @param voiding   compensation must eventually succeed; then parked
     * @param refund    the refund call; then parked (the hold stays in place)
     */
    public record StepTimeouts(
            @DefaultValue("2m") Duration authorize,
            @DefaultValue("10m") Duration capture,
            @DefaultValue("24h") Duration voiding,
            @DefaultValue("10m") Duration refund
    ) {
    }

    public record Retry(
            @DefaultValue("1s") Duration initialBackoff,
            @DefaultValue("30s") Duration maxBackoff
    ) {
    }

    public record Replies(
            @DefaultValue("15s") Duration timeout,
            @DefaultValue("5m") Duration maxTimeout
    ) {
    }

    public Duration stepTimeout(SagaState state) {
        return switch (state) {
            case AUTHORIZING -> stepTimeouts.authorize();
            case CAPTURING -> stepTimeouts.capture();
            case VOIDING -> stepTimeouts.voiding();
            case REFUNDING -> stepTimeouts.refund();
            default -> throw new IllegalArgumentException("No processor step timeout for " + state);
        };
    }
}
