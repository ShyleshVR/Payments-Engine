package com.shylesh.payout_service.batch;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import lombok.extern.slf4j.Slf4j;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

/**
 * When a payout batch last completed, read from the database so every replica reports the same
 * value whichever one ran it (the PayoutBatchNotRun alert). 0 until the first batch.
 */
@Slf4j
@Component
public class BatchMetrics {

    private final PayoutBatchRepository batchRepository;
    private final AtomicLong lastSuccessEpochSeconds = new AtomicLong();
    private final AtomicLong lastCreated = new AtomicLong();
    private final AtomicLong lastSkipped = new AtomicLong();

    public BatchMetrics(PayoutBatchRepository batchRepository, MeterRegistry registry) {
        this.batchRepository = batchRepository;
        Gauge.builder("payout.batch.last.success.timestamp", lastSuccessEpochSeconds, AtomicLong::get)
                .baseUnit("seconds")
                .description("When the most recent payout batch completed (epoch seconds, 0 = never)")
                .register(registry);
        Gauge.builder("payout.batch.latest.created", lastCreated, AtomicLong::get)
                .description("Payouts created by the most recent completed batch")
                .register(registry);
        Gauge.builder("payout.batch.latest.skipped", lastSkipped, AtomicLong::get)
                .description("Merchants the most recent completed batch skipped for lack of a payout destination")
                .register(registry);
    }

    @Scheduled(initialDelay = 0, fixedDelayString = "${payflow.payouts.metrics-refresh:PT30S}")
    public void refresh() {
        try {
            batchRepository.findFirstByStatusOrderByFinishedAtDesc(PayoutBatch.Status.COMPLETED).ifPresent(batch -> {
                lastSuccessEpochSeconds.set(batch.getFinishedAt().toEpochSecond(ZoneOffset.UTC));
                lastCreated.set(batch.getPayoutsCreated() == null ? 0 : batch.getPayoutsCreated());
                lastSkipped.set(batch.getSkippedNoDestination() == null ? 0 : batch.getSkippedNoDestination());
            });
        } catch (RuntimeException e) {
            log.warn("Could not refresh payout batch metrics: {}", e.getMessage());
        }
    }
}
