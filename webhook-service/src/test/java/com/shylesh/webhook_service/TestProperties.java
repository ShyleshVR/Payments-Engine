package com.shylesh.webhook_service;

import com.shylesh.webhook_service.config.WebhookProperties;

import java.time.Duration;

public final class TestProperties {

    private TestProperties() {
    }

    public static WebhookProperties defaults() {
        return with(true, false);
    }

    public static WebhookProperties with(boolean requireHttps, boolean allowPrivateTargets) {
        return new WebhookProperties(
                new WebhookProperties.Delivery(100, 4, Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(10)),
                new WebhookProperties.Retry(5, Duration.ofSeconds(30), Duration.ofMinutes(8)),
                new WebhookProperties.Security(requireHttps, allowPrivateTargets)
        );
    }
}
