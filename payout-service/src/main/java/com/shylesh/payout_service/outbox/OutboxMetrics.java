package com.shylesh.payout_service.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import lombok.extern.slf4j.Slf4j;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.function.ToDoubleFunction;

/**
 * The outbox backlog, for alerting: messages waiting to be published, messages parked for an
 * operator, and how long the oldest waiting message has waited. Read from the database in the
 * background, so every replica reports the same numbers and a scrape never waits on the database
 * (when it did, a service whose database was gone had its whole metrics endpoint fail, and looked
 * down to Prometheus). While the database cannot be read the values are unknown, and reported as
 * NaN: no alert fires on numbers nobody could read.
 */
@Slf4j
@Component
public class OutboxMetrics {

    private final OutboxEventRepository repository;
    private volatile Reading reading;

    private record Reading(long pending, long failed, LocalDateTime oldestPending) {
    }

    public OutboxMetrics(OutboxEventRepository repository, MeterRegistry registry) {
        this.repository = repository;
        Gauge.builder("outbox.pending", this, metrics -> metrics.value(r -> r.pending()))
                .description("Outbox messages not yet published")
                .register(registry);
        Gauge.builder("outbox.failed", this, metrics -> metrics.value(r -> r.failed()))
                .description("Outbox messages parked as FAILED (they block later messages of their payout)")
                .register(registry);
        Gauge.builder("outbox.oldest.pending.age", this, metrics -> metrics.value(OutboxMetrics::ageSeconds))
                .baseUnit("seconds")
                .description("Age of the oldest unpublished outbox message (0 when none)")
                .register(registry);
    }

    @Scheduled(initialDelay = 0, fixedDelayString = "${payflow.metrics.refresh-interval:PT10S}")
    public void refresh() {
        try {
            reading = new Reading(repository.countByStatus(OutboxEventStatus.PENDING),
                    repository.countByStatus(OutboxEventStatus.FAILED),
                    repository.findOldestCreatedAt(OutboxEventStatus.PENDING));
        } catch (RuntimeException e) {
            reading = null;
            log.warn("Could not refresh outbox metrics: {}", e.getMessage());
        }
    }

    private double value(ToDoubleFunction<Reading> of) {
        Reading current = reading;
        return current == null ? Double.NaN : of.applyAsDouble(current);
    }

    private static double ageSeconds(Reading reading) {
        LocalDateTime at = reading.oldestPending();
        return at == null ? 0 : Math.max(0, Duration.between(at, LocalDateTime.now()).toSeconds());
    }
}
