package com.shylesh.payout_service.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties(prefix = "outbox")
public record OutboxProperties(
        @DefaultValue Publisher publisher,
        @DefaultValue Cleanup cleanup
) {

    /**
     * @param batchSize      max messages published per poll
     * @param sendTimeout    how long to wait for the broker ack before treating the send as failed
     * @param initialBackoff delay before the first retry of a failed send; doubles per attempt
     * @param maxBackoff     cap on the retry delay
     */
    public record Publisher(
            @DefaultValue("100") int batchSize,
            @DefaultValue("15s") Duration sendTimeout,
            @DefaultValue("1s") Duration initialBackoff,
            @DefaultValue("5m") Duration maxBackoff
    ) {
    }

    /** @param retention how long PUBLISHED messages are kept before deletion */
    public record Cleanup(
            @DefaultValue("7d") Duration retention
    ) {
    }
}
