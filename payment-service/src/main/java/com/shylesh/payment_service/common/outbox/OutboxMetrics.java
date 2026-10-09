package com.shylesh.payment_service.common.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * The outbox backlog, for alerting: messages waiting to be published, messages parked for an
 * operator, and how long the oldest waiting message has waited. Read from the database at each
 * scrape, so every replica reports the same numbers.
 */
@Component
public class OutboxMetrics {

    public OutboxMetrics(OutboxEventRepository repository, MeterRegistry registry) {
        Gauge.builder("outbox.pending", repository, r -> r.countByStatus(OutboxEventStatus.PENDING))
                .description("Outbox messages not yet published")
                .register(registry);
        Gauge.builder("outbox.failed", repository, r -> r.countByStatus(OutboxEventStatus.FAILED))
                .description("Outbox messages parked as FAILED (they block later messages of their payment)")
                .register(registry);
        Gauge.builder("outbox.oldest.pending.age", repository, OutboxMetrics::oldestPendingAgeSeconds)
                .baseUnit("seconds")
                .description("Age of the oldest unpublished outbox message (0 when none)")
                .register(registry);
    }

    private static double oldestPendingAgeSeconds(OutboxEventRepository repository) {
        LocalDateTime oldest = repository.findOldestCreatedAt(OutboxEventStatus.PENDING);
        return oldest == null ? 0 : Math.max(0, Duration.between(oldest, LocalDateTime.now()).toSeconds());
    }
}
