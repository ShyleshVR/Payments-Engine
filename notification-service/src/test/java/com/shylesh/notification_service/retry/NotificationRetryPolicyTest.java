package com.shylesh.notification_service.retry;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationRetryPolicyTest {

    private final NotificationRetryPolicy policy = new NotificationRetryPolicy();

    @Test
    void allowsRetryBelowMaxAttempts() {
        assertThat(policy.canRetry(1)).isTrue();
        assertThat(policy.canRetry(4)).isTrue();
    }

    @Test
    void disallowsRetryAtMaxAttempts() {
        assertThat(policy.canRetry(5)).isFalse();
        assertThat(policy.canRetry(6)).isFalse();
    }

    @Test
    void delayGrowsWithAttemptCount() {
        LocalDateTime now = LocalDateTime.now();

        long delayAfterAttempt1 = ChronoUnit.MILLIS.between(now, policy.nextAttemptAt(1));
        long delayAfterAttempt3 = ChronoUnit.MILLIS.between(now, policy.nextAttemptAt(3));

        assertThat(delayAfterAttempt3).isGreaterThan(delayAfterAttempt1);
    }

    @Test
    void delayIsCappedAtMaxDelay() {
        LocalDateTime now = LocalDateTime.now();

        long delayMillis = ChronoUnit.MILLIS.between(now, policy.nextAttemptAt(10));

        assertThat(delayMillis).isLessThanOrEqualTo(java.time.Duration.ofMinutes(8).plusSeconds(120).toMillis());
    }
}
