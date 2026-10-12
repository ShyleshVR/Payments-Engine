package com.shylesh.reconciliation_service.service;

import com.shylesh.reconciliation_service.config.ReconciliationProperties;
import com.shylesh.reconciliation_service.persistence.ReconciliationRunRepository;
import com.shylesh.reconciliation_service.persistence.RunStatus;
import com.shylesh.reconciliation_service.persistence.RunTrigger;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockExtender;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Runs the reconciliation of the previous day every night, and an hourly catch-up that
 * reconciles recent days without a completed run (missed while down, or failed because a source
 * was unavailable). Every replica schedules both; a ShedLock row in Postgres lets one of them run
 * at a time, and a day with a completed run is never reconciled again by the scheduler. The lock
 * is held for 10 minutes at most and renewed by the run while it works.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DailyReconciliationJob {

    static final String LOCK_NAME = "daily-reconciliation";
    /**
     * How long the lock outlives a pod that was killed while holding it. A run takes as long as
     * its day is big (millions of payments: half an hour), so the lock is not sized for the
     * longest run: it is short, and a run renews it at every heartbeat (about every 30 seconds).
     */
    static final Duration LOCK_AT_MOST = Duration.ofMinutes(10);
    static final Duration LOCK_AT_LEAST = Duration.ofSeconds(10);

    private final ReconciliationService reconciliationService;
    private final ReconciliationRunRepository runRepository;
    private final ReconciliationProperties properties;
    private final LockingTaskExecutor lockingTaskExecutor;
    private final Clock clock;

    @Scheduled(cron = "${payflow.reconciliation.cron:0 0 2 * * *}", zone = "UTC")
    public void reconcileYesterday() {
        withLock(() -> runIfNeeded(LocalDate.now(clock).minusDays(1)));
    }

    @Scheduled(initialDelayString = "${payflow.reconciliation.catch-up-initial-delay:PT1M}",
            fixedDelayString = "${payflow.reconciliation.catch-up-interval:PT1H}")
    public void catchUp() {
        withLock(() -> {
            LocalDate today = LocalDate.now(clock);
            for (int daysAgo = properties.catchUpDays(); daysAgo >= 1; daysAgo--) {
                runIfNeeded(today.minusDays(daysAgo));
            }
        });
    }

    /** Reconciles the day unless it already has a completed run or isn't over yet (plus the margin). */
    void runIfNeeded(LocalDate day) {
        if (runRepository.existsByBusinessDateAndStatus(day, RunStatus.COMPLETED)) {
            return;
        }
        LocalDateTime closesAt = day.plusDays(1).atStartOfDay().plus(properties.matchingMargin());
        if (LocalDateTime.now(clock).isBefore(closesAt)) {
            return;
        }
        try {
            reconciliationService.run(day, RunTrigger.SCHEDULED, this::renewLock);
        } catch (RunInProgressException e) {
            log.info("Skipping {}: {}", day, e.getMessage());
        }
    }

    /**
     * Called by the run on this thread, inside withLock. If the lock can't be renewed (this pod
     * stalled for longer than the lock lasts and it expired), the run carries on: a day is
     * protected by its RUNNING run, not by this lock, which only keeps replicas from working at
     * the same time.
     */
    private void renewLock() {
        try {
            LockExtender.extendActiveLock(LOCK_AT_MOST, LOCK_AT_LEAST);
        } catch (LockExtender.LockExtensionException e) {
            log.warn("Could not renew the scheduler lock: {}", e.getMessage());
        }
    }

    private void withLock(Runnable task) {
        lockingTaskExecutor.executeWithLock(task,
                new LockConfiguration(clock.instant(), LOCK_NAME, LOCK_AT_MOST, LOCK_AT_LEAST));
    }
}
