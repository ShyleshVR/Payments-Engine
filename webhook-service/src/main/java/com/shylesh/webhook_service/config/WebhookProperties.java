package com.shylesh.webhook_service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties(prefix = "webhook")
public record WebhookProperties(
        @DefaultValue Delivery delivery,
        @DefaultValue Retry retry,
        @DefaultValue Security security
) {

    /**
     * @param maxInFlight    max deliveries queued or being sent per instance (bounds memory and DB load)
     * @param workers        deliveries sent in parallel, so one slow merchant can't hold up the rest
     * @param connectTimeout TCP connect timeout for the merchant endpoint
     * @param readTimeout    time allowed for the merchant to respond
     * @param lease          how long a claimed delivery is hidden from other dispatchers
     */
    public record Delivery(
            @DefaultValue("100") int maxInFlight,
            @DefaultValue("8") int workers,
            @DefaultValue("3s") Duration connectTimeout,
            @DefaultValue("10s") Duration readTimeout,
            @DefaultValue("2m") Duration lease
    ) {
        public Delivery {
            // A send must finish well inside the lease, or another dispatcher could re-claim
            // the delivery while the first request is still in flight and send it twice.
            Duration worstCaseSend = connectTimeout.plus(readTimeout);
            if (lease.compareTo(worstCaseSend.multipliedBy(2)) < 0) {
                throw new IllegalArgumentException(
                        "webhook.delivery.lease (" + lease + ") must be at least twice connect-timeout + read-timeout ("
                                + worstCaseSend + ")");
            }
        }
    }

    /**
     * Exponential backoff: baseDelay doubling per attempt, capped at maxDelay, +/-20% jitter.
     */
    public record Retry(
            @DefaultValue("5") int maxAttempts,
            @DefaultValue("30s") Duration baseDelay,
            @DefaultValue("8m") Duration maxDelay
    ) {
    }

    /**
     * @param requireHttps        reject plain-http endpoints (payloads and signatures would travel in clear text)
     * @param allowPrivateTargets allow loopback/private/link-local addresses; only for local development,
     *                            otherwise the subscription API could be used to reach internal services (SSRF)
     */
    public record Security(
            @DefaultValue("true") boolean requireHttps,
            @DefaultValue("false") boolean allowPrivateTargets
    ) {
    }
}
