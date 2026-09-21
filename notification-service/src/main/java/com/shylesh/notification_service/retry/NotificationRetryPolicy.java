package com.shylesh.notification_service.retry;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Exponential backoff with jitter: delay doubles per attempt, capped at MAX_DELAY, with up
 * to +/-20% jitter so many notifications failing at once don't all retry in lockstep.
 */
@Component
public class NotificationRetryPolicy {

    public static final int MAX_ATTEMPTS = 5;

    private static final Duration BASE_DELAY = Duration.ofSeconds(30);
    private static final Duration MAX_DELAY = Duration.ofMinutes(8);

    public boolean canRetry(int attemptCount) {
        return attemptCount < MAX_ATTEMPTS;
    }

    public LocalDateTime nextAttemptAt(int attemptCount) {
        long uncappedMillis = BASE_DELAY.toMillis() * (1L << (attemptCount - 1));
        long cappedMillis = Math.min(uncappedMillis, MAX_DELAY.toMillis());

        long jitterRange = cappedMillis / 5;
        long jitterMillis = jitterRange == 0 ? 0 : ThreadLocalRandom.current().nextLong(-jitterRange, jitterRange + 1);

        long delayMillis = Math.max(1000L, cappedMillis + jitterMillis);
        return LocalDateTime.now().plus(Duration.ofMillis(delayMillis));
    }
}
