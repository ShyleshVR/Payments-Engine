package com.shylesh.payout_service.batch;

import com.shylesh.payout_service.config.PayoutProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;

/**
 * Runs the payout batch daily. Every replica schedules it; a ShedLock row in Postgres lets one at
 * a time run it, and a day with a completed batch is not run again by the scheduler. An hourly
 * catch-up runs a day whose batch was missed (the service was down at batch time, or the ledger
 * was unreachable). Operators can run it on demand (also under the lock).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DailyPayoutJob {

    static final String LOCK_NAME = "daily-payout-batch";
    static final Duration LOCK_AT_MOST = Duration.ofMinutes(30);
    static final Duration LOCK_AT_LEAST = Duration.ofSeconds(5);

    private final PayoutBatchService batchService;
    private final PayoutBatchRepository batchRepository;
    private final PayoutProperties properties;
    private final LockingTaskExecutor lockingTaskExecutor;
    private final Clock clock;

    @Scheduled(cron = "${payflow.payouts.batch.cron:0 0 4 * * *}", zone = "UTC")
    public void runToday() {
        withLock(() -> runIfNeeded(LocalDate.now(clock)));
    }

    @Scheduled(initialDelayString = "${payflow.payouts.batch.catch-up-initial-delay:PT1M}",
            fixedDelayString = "${payflow.payouts.batch.catch-up-interval:PT1H}")
    public void catchUp() {
        if (LocalTime.now(clock).isBefore(properties.batch().time())) {
            return;
        }
        withLock(() -> runIfNeeded(LocalDate.now(clock)));
    }

    /**
     * Operator: run today's batch now, whatever the time and even if it already ran (it only
     * creates what is missing).
     *
     * @return the batch, or empty if another run holds the lock
     */
    public Optional<PayoutBatch> runNow() {
        try {
            var result = lockingTaskExecutor.executeWithLock(() -> batchService.run(LocalDate.now(clock)), lockConfiguration());
            return result.wasExecuted() ? Optional.ofNullable(result.getResult()) : Optional.empty();
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }

    void runIfNeeded(LocalDate day) {
        boolean done = batchRepository.findById(day).map(b -> b.getStatus() == PayoutBatch.Status.COMPLETED).orElse(false);
        if (!done) {
            batchService.run(day);
        }
    }

    private void withLock(Runnable task) {
        lockingTaskExecutor.executeWithLock(task, lockConfiguration());
    }

    private LockConfiguration lockConfiguration() {
        return new LockConfiguration(clock.instant(), LOCK_NAME, LOCK_AT_MOST, LOCK_AT_LEAST);
    }
}
