package com.shylesh.payment_service.saga;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * How long the oldest in-progress saga step has been running, per saga type: a step that takes
 * minutes means a dependency is down or a saga is stuck (alert SagaStepStuck). Steps waiting for
 * the merchant (MANUAL capture) or parked for an operator are excluded; parked sagas have their
 * own gauge, sagas.requires_attention.
 */
@Component
public class SagaMetrics {

    public SagaMetrics(PaymentSagaRepository repository, MeterRegistry registry) {
        for (SagaType type : SagaType.values()) {
            Gauge.builder("sagas.oldest.step.age", repository, r -> ageSeconds(r.findOldestStepStartInProgress(type)))
                    .tag("type", type.name())
                    .baseUnit("seconds")
                    .description("Age of the oldest saga step in progress (0 when none)")
                    .register(registry);
        }
    }

    private static double ageSeconds(LocalDateTime startedAt) {
        return startedAt == null ? 0 : Math.max(0, Duration.between(startedAt, LocalDateTime.now()).toSeconds());
    }
}
