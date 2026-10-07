package com.shylesh.webhook_service.retry;

import com.shylesh.webhook_service.TestProperties;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookRetryPolicyTest {

    private final WebhookRetryPolicy policy = new WebhookRetryPolicy(TestProperties.defaults());

    @Test
    void allowsRetriesUntilMaxAttempts() {
        assertThat(policy.canRetry(4)).isTrue();
        assertThat(policy.canRetry(5)).isFalse();
    }

    @RepeatedTest(20)
    void delayDoublesWithinTwentyPercentJitter() {
        assertWithinJitter(policy.delay(1), Duration.ofSeconds(30));
        assertWithinJitter(policy.delay(2), Duration.ofSeconds(60));
        assertWithinJitter(policy.delay(3), Duration.ofSeconds(120));
    }

    @RepeatedTest(20)
    void delayIsCappedAtMaxDelay() {
        assertWithinJitter(policy.delay(10), Duration.ofMinutes(8));
    }

    private void assertWithinJitter(Duration actual, Duration nominal) {
        long nominalMillis = nominal.toMillis();
        assertThat(actual.toMillis()).isBetween(nominalMillis * 8 / 10, nominalMillis * 12 / 10);
    }
}
