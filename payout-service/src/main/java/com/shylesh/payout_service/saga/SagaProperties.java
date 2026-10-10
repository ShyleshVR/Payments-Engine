package com.shylesh.payout_service.saga;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param pollInterval        how often the worker looks for due sagas
 * @param workers             bank calls in parallel per instance
 * @param maxInFlight         sagas queued or running per instance
 * @param lease               how long a claimed step is hidden from other workers; must outlast
 *                            a bank call (connect + read timeout)
 * @param submitTimeout       how long a transfer request may stay unanswered before the saga
 *                            stops for an operator (the money may have moved: never released blindly)
 * @param inTransitTimeout    how long a transfer may stay in transit before the saga stops for
 *                            an operator
 * @param transitPollInterval how often an in-transit transfer is checked
 * @param returnWindow        how long after being paid a payout is watched for a return
 * @param returnCheckInterval how often a paid payout is checked for a return
 * @param retry               backoff between attempts of a bank call with an unknown outcome
 * @param replies             how long to wait for a ledger reply before re-sending the command
 */
@ConfigurationProperties(prefix = "payflow.payouts.saga")
public record SagaProperties(
        @DefaultValue("500ms") Duration pollInterval,
        @DefaultValue("4") int workers,
        @DefaultValue("100") int maxInFlight,
        @DefaultValue("30s") Duration lease,
        @DefaultValue("10m") Duration submitTimeout,
        @DefaultValue("3d") Duration inTransitTimeout,
        @DefaultValue("1h") Duration transitPollInterval,
        @DefaultValue("5d") Duration returnWindow,
        @DefaultValue("1h") Duration returnCheckInterval,
        @DefaultValue Retry retry,
        @DefaultValue Replies replies
) {

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
}
