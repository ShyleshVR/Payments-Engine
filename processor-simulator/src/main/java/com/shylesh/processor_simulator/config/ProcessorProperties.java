package com.shylesh.processor_simulator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param apiKey           callers send it as X-Api-Key
 * @param timeoutOnceDelay pm_card_timeout_once: delay of the first authorization answer
 * @param flakyFailures    pm_card_flaky: calls per Idempotency-Key answered 503 before one succeeds
 * @param chaos            failures injected into any request, for load and resilience tests
 * @param bank             timing of bank transfers (payouts)
 */
@ConfigurationProperties(prefix = "processor")
public record ProcessorProperties(
        String apiKey,
        @DefaultValue("8s") Duration timeoutOnceDelay,
        @DefaultValue("2") int flakyFailures,
        @DefaultValue Chaos chaos,
        @DefaultValue Bank bank
) {

    public ProcessorProperties {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("processor.api-key must be set");
        }
    }

    /**
     * @param errorRate share of requests answered 503 without being processed (0.0 - 1.0)
     * @param latency   added to every request
     */
    public record Chaos(
            @DefaultValue("0.0") double errorRate,
            @DefaultValue("0ms") Duration latency
    ) {
    }

    /**
     * @param settleDelay     how long a transfer stays PENDING before it is paid (or fails)
     * @param slowSettleDelay the same for ba_test_slow
     * @param returnDelay     how long after being paid a returning transfer comes back
     * @param clockInterval   how often the bank moves due transfers on
     */
    public record Bank(
            @DefaultValue("20s") Duration settleDelay,
            @DefaultValue("10m") Duration slowSettleDelay,
            @DefaultValue("60s") Duration returnDelay,
            @DefaultValue("2s") Duration clockInterval
    ) {
    }
}
