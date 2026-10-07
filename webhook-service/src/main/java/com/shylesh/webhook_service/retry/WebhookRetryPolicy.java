package com.shylesh.webhook_service.retry;

import com.shylesh.webhook_service.config.WebhookProperties;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Exponential backoff with jitter: delay doubles per attempt from baseDelay, capped at
 * maxDelay, +/-20% jitter so a merchant outage doesn't make every retry land at once.
 */
@Component
@RequiredArgsConstructor
public class WebhookRetryPolicy {

    private final WebhookProperties properties;

    public boolean canRetry(int attemptCount) {
        return attemptCount < properties.retry().maxAttempts();
    }

    public LocalDateTime nextAttemptAt(int attemptCount) {
        return LocalDateTime.now().plus(delay(attemptCount));
    }

    Duration delay(int attemptCount) {
        long baseMillis = properties.retry().baseDelay().toMillis();
        long maxMillis = properties.retry().maxDelay().toMillis();

        long uncappedMillis = baseMillis * (1L << Math.min(attemptCount - 1, 20));
        long cappedMillis = Math.min(uncappedMillis, maxMillis);

        long jitterRange = cappedMillis / 5;
        long jitterMillis = jitterRange == 0 ? 0 : ThreadLocalRandom.current().nextLong(-jitterRange, jitterRange + 1);

        return Duration.ofMillis(Math.max(1000L, cappedMillis + jitterMillis));
    }
}
