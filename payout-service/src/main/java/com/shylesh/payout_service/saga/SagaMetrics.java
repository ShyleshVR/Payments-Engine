package com.shylesh.payout_service.saga;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * How long the oldest payout step in progress has been running, read from the database at
 * scrape time, under the same name and labels as payment-service's, so the same SagaStepStuck
 * alert covers both. Waiting on the bank (in transit, return window) is excluded: those steps
 * last days by design and have their own timeouts.
 */
@Slf4j
@Component
public class SagaMetrics {

    public SagaMetrics(PayoutSagaRepository sagaRepository, MeterRegistry registry) {
        Gauge.builder("sagas.oldest.step.age", sagaRepository, repository -> {
                    try {
                        LocalDateTime oldest = repository.findOldestStepStartInProgress();
                        return oldest == null ? 0 : Math.max(0, Duration.between(oldest, LocalDateTime.now()).toSeconds());
                    } catch (RuntimeException e) {
                        log.debug("Could not read the oldest payout step: {}", e.getMessage());
                        return Double.NaN;
                    }
                })
                .tag("type", "PAYOUT")
                .baseUnit("seconds")
                .description("How long the oldest payout step in progress has been running")
                .register(registry);
    }
}
