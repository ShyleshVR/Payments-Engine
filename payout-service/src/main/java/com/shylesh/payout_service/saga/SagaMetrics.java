package com.shylesh.payout_service.saga;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import lombok.extern.slf4j.Slf4j;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * How long the oldest payout step in progress has been running, under the same name and labels
 * as payment-service's, so the same SagaStepStuck alert covers both. Waiting on the bank (in
 * transit, return window) is excluded: those steps last days by design and have their own
 * timeouts. Also the payout sagas parked for an operator (SagaRequiresAttention).
 *
 * Read from the database in the background, not at scrape time, so the metrics endpoint answers
 * even when the database does not. While it cannot be read the values are unknown, reported as NaN.
 */
@Slf4j
@Component
public class SagaMetrics {

    private final PayoutSagaRepository sagaRepository;
    private volatile Reading reading;

    private record Reading(LocalDateTime oldestStepStart, long requiresAttention) {
    }

    public SagaMetrics(PayoutSagaRepository sagaRepository, MeterRegistry registry) {
        this.sagaRepository = sagaRepository;
        Gauge.builder("sagas.oldest.step.age", this, SagaMetrics::ageSeconds)
                .tag("type", "PAYOUT")
                .baseUnit("seconds")
                .description("How long the oldest payout step in progress has been running")
                .register(registry);
        Gauge.builder("sagas.requires_attention", this, SagaMetrics::requiresAttention)
                .description("Payout sagas parked for an operator")
                .register(registry);
    }

    @Scheduled(initialDelay = 0, fixedDelayString = "${payflow.metrics.refresh-interval:PT10S}")
    public void refresh() {
        try {
            reading = new Reading(sagaRepository.findOldestStepStartInProgress(),
                    sagaRepository.countByState(SagaState.REQUIRES_ATTENTION));
        } catch (RuntimeException e) {
            reading = null;
            log.warn("Could not refresh payout saga metrics: {}", e.getMessage());
        }
    }

    private double ageSeconds() {
        Reading current = reading;
        if (current == null) {
            return Double.NaN;
        }
        LocalDateTime at = current.oldestStepStart();
        return at == null ? 0 : Math.max(0, Duration.between(at, LocalDateTime.now()).toSeconds());
    }

    private double requiresAttention() {
        Reading current = reading;
        return current == null ? Double.NaN : current.requiresAttention();
    }
}
