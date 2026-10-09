package com.shylesh.processor_simulator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param apiKey           callers send it as X-Api-Key
 * @param timeoutOnceDelay pm_card_timeout_once: delay of the first authorization answer
 * @param flakyFailures    pm_card_flaky: calls per Idempotency-Key answered 503 before one succeeds
 * @param chaos            failures injected into any request, for load and resilience tests
 */
@ConfigurationProperties(prefix = "processor")
public record ProcessorProperties(
        String apiKey,
        @DefaultValue("8s") Duration timeoutOnceDelay,
        @DefaultValue("2") int flakyFailures,
        @DefaultValue Chaos chaos
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
}
