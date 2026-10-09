package com.shylesh.reconciliation_service.service;

import com.shylesh.reconciliation_service.domain.DiscrepancyType;
import com.shylesh.reconciliation_service.persistence.ReconciliationDiscrepancyRepository;
import com.shylesh.reconciliation_service.persistence.ReconciliationRun;
import com.shylesh.reconciliation_service.persistence.ReconciliationRunRepository;
import com.shylesh.reconciliation_service.persistence.RunStatus;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import lombok.extern.slf4j.Slf4j;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Reconciliation gauges, read from the database, so every replica reports the same values
 * whichever replica ran the job. Alerts use them: discrepancies still standing in recent days, and
 * how long ago a run last succeeded.
 */
@Slf4j
@Component
public class ReconciliationMetrics {

    /** How many recent business days the discrepancy gauge covers. */
    static final int OPEN_WINDOW_DAYS = 7;

    private final ReconciliationRunRepository runRepository;
    private final ReconciliationDiscrepancyRepository discrepancyRepository;
    private final Clock clock;

    private final AtomicLong lastSuccessEpochSeconds = new AtomicLong();
    private final AtomicLong checked = new AtomicLong();
    private final AtomicLong matched = new AtomicLong();
    private final AtomicLong pending = new AtomicLong();
    private final Map<DiscrepancyType, AtomicLong> discrepancies = new EnumMap<>(DiscrepancyType.class);

    public ReconciliationMetrics(ReconciliationRunRepository runRepository,
                                 ReconciliationDiscrepancyRepository discrepancyRepository,
                                 MeterRegistry registry, Clock clock) {
        this.runRepository = runRepository;
        this.discrepancyRepository = discrepancyRepository;
        this.clock = clock;

        Gauge.builder("reconciliation.last.success.timestamp", lastSuccessEpochSeconds, AtomicLong::get)
                .baseUnit("seconds")
                .description("When the most recent reconciliation run completed (epoch seconds, 0 = never)")
                .register(registry);
        Gauge.builder("reconciliation.latest.checked", checked, AtomicLong::get).register(registry);
        Gauge.builder("reconciliation.latest.matched", matched, AtomicLong::get).register(registry);
        Gauge.builder("reconciliation.latest.pending", pending, AtomicLong::get).register(registry);
        for (DiscrepancyType type : DiscrepancyType.values()) {
            AtomicLong count = new AtomicLong();
            discrepancies.put(type, count);
            Gauge.builder("reconciliation.discrepancies", count, AtomicLong::get)
                    .tag("type", type.name())
                    .description("Discrepancies standing in recent days (latest completed run of each day)")
                    .register(registry);
        }
    }

    @Scheduled(initialDelay = 0, fixedDelayString = "${payflow.reconciliation.metrics-refresh:PT30S}")
    public void refresh() {
        try {
            runRepository.findFirstByStatusOrderByFinishedAtDesc(RunStatus.COMPLETED).ifPresent(this::showLatest);
            showOpenDiscrepancies();
        } catch (RuntimeException e) {
            log.warn("Could not refresh reconciliation metrics: {}", e.getMessage());
        }
    }

    private void showLatest(ReconciliationRun run) {
        lastSuccessEpochSeconds.set(run.getFinishedAt().toEpochSecond(ZoneOffset.UTC));
        checked.set(nullToZero(run.getPaymentsChecked()));
        matched.set(nullToZero(run.getMatched()));
        pending.set(nullToZero(run.getPending()));
    }

    /**
     * For each business day of the last OPEN_WINDOW_DAYS, the discrepancies of that day's most
     * recent completed run. A day keeps alerting until it is re-run clean, whichever other days
     * were reconciled after it.
     */
    private void showOpenDiscrepancies() {
        LocalDate since = LocalDate.now(clock).minusDays(OPEN_WINDOW_DAYS);
        Map<LocalDate, ReconciliationRun> latestPerDay = new HashMap<>();
        for (ReconciliationRun run : runRepository.findByStatusAndBusinessDateGreaterThanEqual(RunStatus.COMPLETED, since)) {
            latestPerDay.merge(run.getBusinessDate(), run,
                    (a, b) -> a.getFinishedAt().isAfter(b.getFinishedAt()) ? a : b);
        }
        Map<DiscrepancyType, Long> byType = new EnumMap<>(DiscrepancyType.class);
        for (ReconciliationRun run : latestPerDay.values()) {
            discrepancyRepository.findByRunIdOrderByIdAsc(run.getId())
                    .forEach(d -> byType.merge(d.getType(), 1L, Long::sum));
        }
        discrepancies.forEach((type, count) -> count.set(byType.getOrDefault(type, 0L)));
    }

    private static long nullToZero(Integer value) {
        return value == null ? 0 : value;
    }
}

