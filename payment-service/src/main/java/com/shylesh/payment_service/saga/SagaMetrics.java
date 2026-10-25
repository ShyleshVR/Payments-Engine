package com.shylesh.payment_service.saga;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import lombok.extern.slf4j.Slf4j;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.Map;

/**
 * How long the oldest in-progress saga step has been running, per saga type: a step that takes
 * minutes means a dependency is down or a saga is stuck (alert SagaStepStuck). Steps waiting for
 * the merchant (MANUAL capture) or parked for an operator are excluded; parked sagas are counted
 * by sagas.requires_attention (alert SagaRequiresAttention).
 *
 * Read from the database in the background, not at scrape time, so the metrics endpoint answers
 * even when the database does not. While it cannot be read the values are unknown, reported as NaN.
 */
@Slf4j
@Component
public class SagaMetrics {

    private final PaymentSagaRepository repository;
    private volatile Reading reading;

    private record Reading(Map<SagaType, LocalDateTime> oldestStepStart, long requiresAttention) {
    }

    public SagaMetrics(PaymentSagaRepository repository, MeterRegistry registry) {
        this.repository = repository;
        for (SagaType type : SagaType.values()) {
            Gauge.builder("sagas.oldest.step.age", this, metrics -> metrics.ageSeconds(type))
                    .tag("type", type.name())
                    .baseUnit("seconds")
                    .description("Age of the oldest saga step in progress (0 when none)")
                    .register(registry);
        }
        Gauge.builder("sagas.requires_attention", this, SagaMetrics::requiresAttention)
                .description("Sagas parked for an operator")
                .register(registry);
    }

    @Scheduled(initialDelay = 0, fixedDelayString = "${payflow.metrics.refresh-interval:PT10S}")
    public void refresh() {
        try {
            Map<SagaType, LocalDateTime> oldest = new EnumMap<>(SagaType.class);
            for (SagaType type : SagaType.values()) {
                oldest.put(type, repository.findOldestStepStartInProgress(type));
            }
            reading = new Reading(oldest, repository.countByState(SagaState.REQUIRES_ATTENTION));
        } catch (RuntimeException e) {
            reading = null;
            log.warn("Could not refresh saga metrics: {}", e.getMessage());
        }
    }

    private double ageSeconds(SagaType type) {
        Reading current = reading;
        if (current == null) {
            return Double.NaN;
        }
        LocalDateTime at = current.oldestStepStart().get(type);
        return at == null ? 0 : Math.max(0, Duration.between(at, LocalDateTime.now()).toSeconds());
    }

    private double requiresAttention() {
        Reading current = reading;
        return current == null ? Double.NaN : current.requiresAttention();
    }
}
