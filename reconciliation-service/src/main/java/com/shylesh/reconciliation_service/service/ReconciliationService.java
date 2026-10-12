package com.shylesh.reconciliation_service.service;

import com.shylesh.reconciliation_service.client.SourceClient;
import com.shylesh.reconciliation_service.config.ReconciliationProperties;
import com.shylesh.reconciliation_service.domain.Reconciler;
import com.shylesh.reconciliation_service.domain.Records;
import com.shylesh.reconciliation_service.persistence.ReconciliationDiscrepancy;
import com.shylesh.reconciliation_service.persistence.ReconciliationDiscrepancyRepository;
import com.shylesh.reconciliation_service.persistence.ReconciliationRun;
import com.shylesh.reconciliation_service.persistence.ReconciliationRunRepository;
import com.shylesh.reconciliation_service.persistence.RunStatus;
import com.shylesh.reconciliation_service.persistence.RunTrigger;
import com.shylesh.reconciliation_service.persistence.StagingStore;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Reconciles one business day: reads the processor, the ledger, payment-service and
 * payout-service, compares them (Reconciler), and stores the run with its discrepancies. A source
 * that can't be read fails the run (recorded, retried by the hourly catch-up) rather than
 * producing a partial result that would look like discrepancies.
 *
 * A day is never held in memory, so its size doesn't matter:
 * 1. the day's records are copied from the processor and the ledger into a staging table, a page
 *    at a time (StagingStore);
 * 2. the payments staged are reconciled in id order, a chunk at a time: everything staged for
 *    those payments is loaded, their payments looked up, and the Reconciler run on that chunk;
 * 3. the payouts the same way;
 * 4. holds that stayed open too long are checked once (they don't depend on the day's records).
 * Every payment and payout is in exactly one chunk with all its records, so adding up the chunks'
 * counts gives the day's.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReconciliationService {

    static final String ABANDONED = "Abandoned: the run stopped reporting (its pod was probably killed); taken over by a new run";
    /** How far back ended runs are checked for staged rows they left behind. */
    private static final Duration LEFTOVERS_OF = Duration.ofDays(3);
    /** Discrepancies named in the log line; all of them are in the database. */
    private static final int LOGGED_DISCREPANCIES = 20;

    private final SourceClient sources;
    private final Reconciler reconciler;
    private final ReconciliationRunRepository runRepository;
    private final ReconciliationDiscrepancyRepository discrepancyRepository;
    private final StagingStore staging;
    private final ReconciliationMetrics metrics;
    private final ReconciliationProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    public ReconciliationRun run(LocalDate businessDate, RunTrigger trigger) {
        return run(businessDate, trigger, () -> {
        });
    }

    /** @param onHeartbeat called each time the run has recorded that it is alive (the scheduler renews its lock) */
    public ReconciliationRun run(LocalDate businessDate, RunTrigger trigger, Runnable onHeartbeat) {
        takeOverAbandonedRuns(businessDate);
        ReconciliationRun started;
        try {
            started = transactionTemplate.execute(status ->
                    runRepository.saveAndFlush(ReconciliationRun.start(businessDate, trigger, now())));
        } catch (DataIntegrityViolationException e) {
            throw new RunInProgressException(businessDate);
        }

        Timer.Sample sample = Timer.start(meterRegistry);
        ReconciliationRun run = started;
        try {
            Totals totals = reconcile(started, onHeartbeat);
            transactionTemplate.executeWithoutResult(status -> {
                stillOurs(started);
                started.complete(totals.checked, totals.matched, totals.pending, totals.payoutsChecked, totals.payoutsMatched,
                        totals.payoutsPending, totals.discrepancies, now());
                runRepository.save(started);
            });
            log.info("Reconciled {} ({}): payments checked {}, matched {}, pending {}; payouts checked {}, matched {}, pending {}; discrepancies {}",
                    businessDate, trigger, totals.checked, totals.matched, totals.pending,
                    totals.payoutsChecked, totals.payoutsMatched, totals.payoutsPending, totals.discrepancies);
            if (totals.discrepancies > 0) {
                log.warn("Reconciliation of {} found {} discrepancies{}: {}", businessDate, totals.discrepancies,
                        totals.discrepancies > totals.examples.size() ? " (the first " + totals.examples.size() + ")" : "",
                        totals.examples);
            }
        } catch (RunTakenOverException e) {
            log.warn("Reconciliation of {} stopped: {}", businessDate, e.getMessage());
            // what it stored after it was ended; the run that took over has its own
            transactionTemplate.executeWithoutResult(status -> discrepancyRepository.deleteByRunId(started.getId()));
            run = runRepository.findById(started.getId()).orElse(started);
        } catch (RuntimeException e) {
            log.error("Reconciliation of {} failed: {}", businessDate, e.toString(), e);
            run = fail(started, e);
        } finally {
            deleteStaged(started.getId());
        }
        sample.stop(Timer.builder("reconciliation.duration").tag("status", run.getStatus().name()).register(meterRegistry));
        Counter.builder("reconciliation.runs")
                .tag("status", run.getStatus().name())
                .tag("trigger", trigger.name())
                .register(meterRegistry).increment();
        metrics.refresh();
        return run;
    }

    /**
     * A run whose pod was killed stays RUNNING and would block its day for ever (one run per day
     * at a time). A run that has been silent for abandonedAfter is ended here, so this attempt
     * can start; a live run keeps the day and this attempt is refused as before.
     */
    private void takeOverAbandonedRuns(LocalDate businessDate) {
        LocalDateTime now = now();
        LocalDateTime silentSince = now.minus(properties.abandonedAfter());
        for (ReconciliationRun running : runRepository.findByBusinessDateAndStatus(businessDate, RunStatus.RUNNING)) {
            if (!running.lastSeenAt().isBefore(silentSince)) {
                continue;
            }
            boolean ended = Boolean.TRUE.equals(transactionTemplate.execute(status -> {
                if (runRepository.abandon(running.getId(), ABANDONED, now, silentSince) == 0) {
                    return false;
                }
                discrepancyRepository.deleteByRunId(running.getId());
                return true;
            }));
            if (ended) {
                log.warn("Run {} of {} was last seen at {}: abandoned, taking the day over",
                        running.getId(), businessDate, running.lastSeenAt());
            }
        }
        deleteLeftovers(now);
    }

    /**
     * A run deletes its staged rows when it ends. That can fail (the database was away), and a
     * run ended by a takeover may still have been writing. So every run first deletes what
     * recently ended runs left, by run id: a few index lookups, nothing when all is clean.
     */
    private void deleteLeftovers(LocalDateTime now) {
        int leftovers = 0;
        for (UUID ended : runRepository.findIdsOfRunsEndedSince(now.minus(LEFTOVERS_OF))) {
            leftovers += staging.delete(ended);
        }
        if (leftovers > 0) {
            log.info("Deleted {} staged rows left by runs that are over", leftovers);
        }
    }

    private Totals reconcile(ReconciliationRun run, Runnable onHeartbeat) {
        UUID runId = run.getId();
        LocalDateTime dayStart = run.getBusinessDate().atStartOfDay();
        LocalDateTime dayEnd = dayStart.plusDays(1);
        // a margin either side, so counterparts across midnight are found
        LocalDateTime from = dayStart.minus(properties.matchingMargin());
        LocalDateTime to = dayEnd.plus(properties.matchingMargin());
        LocalDateTime now = now();
        Pulse pulse = new Pulse(run, onHeartbeat);

        // 1. the sources, a page at a time, into the staging table
        long[] staged = new long[4];
        sources.forEachAuthorizationPage(from, to, properties.pageSize(), page -> {
            staging.addAuthorizations(runId, page);
            staged[0] += page.size();
            pulse.beat();
        });
        sources.forEachRefundPage(from, to, properties.pageSize(), page -> {
            staging.addRefunds(runId, page);
            staged[1] += page.size();
            pulse.beat();
        });
        sources.forEachTransferPage(from, to, properties.pageSize(), page -> {
            staging.addTransfers(runId, page);
            staged[2] += page.size();
            pulse.beat();
        });
        sources.forEachLedgerPage(from, to, properties.pageSize(), page -> {
            staging.addLedgerTransactions(runId, page);
            staged[3] += page.size();
            pulse.beat();
        });
        staging.analyze();
        pulse.beat();
        log.info("Read for {}: {} authorizations, {} refunds, {} transfers, {} ledger transactions",
                run.getBusinessDate(), staged[0], staged[1], staged[2], staged[3]);

        Totals totals = new Totals();

        // 2. payments, a chunk at a time
        for (List<UUID> ids = staging.paymentIdsAfter(runId, null, properties.chunkSize()); !ids.isEmpty();
             ids = staging.paymentIdsAfter(runId, ids.getLast(), properties.chunkSize())) {
            StagingStore.Staged records = staging.ofPayments(runId, ids.getFirst(), ids.getLast());
            record(run, totals, reconciler.reconcile(new Records.Snapshot(dayStart, dayEnd, now, records.authorizations(),
                    records.refunds(), records.ledgerTransactions(), List.of(), sources.payments(ids))));
            pulse.beat();
        }

        // 3. payouts, a chunk at a time
        for (List<UUID> ids = staging.payoutIdsAfter(runId, null, properties.chunkSize()); !ids.isEmpty();
             ids = staging.payoutIdsAfter(runId, ids.getLast(), properties.chunkSize())) {
            StagingStore.Staged records = staging.ofPayouts(runId, ids.getFirst(), ids.getLast());
            record(run, totals, reconciler.reconcile(new Records.Snapshot(dayStart, dayEnd, now, List.of(), List.of(),
                    records.ledgerTransactions(), List.of(), Map.of(), records.transfers(), List.of(), sources.payouts(ids))));
            pulse.beat();
        }

        // 4. holds open too long, whatever day they are from
        List<Records.LedgerTransaction> openRefundHolds = sources.openRefundHolds(now.minus(properties.refundHoldStaleAfter()));
        List<Records.LedgerTransaction> openPayoutHolds = sources.openPayoutHolds(now.minus(properties.payoutHoldStaleAfter()));
        Set<UUID> heldPayments = ids(openRefundHolds, true);
        Set<UUID> heldPayouts = ids(openPayoutHolds, false);
        record(run, totals, reconciler.reconcile(new Records.Snapshot(dayStart, dayEnd, now, List.of(), List.of(), List.of(),
                openRefundHolds, sources.payments(heldPayments), List.of(), openPayoutHolds,
                heldPayouts.isEmpty() ? Map.of() : sources.payouts(heldPayouts))));
        return totals;
    }

    private static Set<UUID> ids(List<Records.LedgerTransaction> holds, boolean ofPayments) {
        Set<UUID> ids = new LinkedHashSet<>();
        holds.stream().map(h -> ofPayments ? h.paymentId() : h.payoutId()).filter(Objects::nonNull).forEach(ids::add);
        return ids;
    }

    /** Adds a chunk's counts to the run's and stores its discrepancies. */
    private void record(ReconciliationRun run, Totals totals, Records.Result result) {
        totals.add(result);
        if (result.discrepancies().isEmpty()) {
            return;
        }
        transactionTemplate.executeWithoutResult(status ->
                discrepancyRepository.saveAll(result.discrepancies().stream().map(d -> ReconciliationDiscrepancy.builder()
                        .runId(run.getId())
                        .type(d.type())
                        .paymentId(d.paymentId())
                        .payoutId(d.payoutId())
                        .processorAmount(d.processorAmount())
                        .ledgerAmount(d.ledgerAmount())
                        .paymentStatus(d.paymentStatus())
                        .detail(d.detail())
                        .build()).toList()));
    }

    /** Records the failure, unless the day was taken over meanwhile (then that verdict stands). */
    private ReconciliationRun fail(ReconciliationRun run, RuntimeException cause) {
        return transactionTemplate.execute(status -> {
            if (runRepository.heartbeat(run.getId(), now()) == 0) {
                return runRepository.findById(run.getId()).orElse(run);
            }
            discrepancyRepository.deleteByRunId(run.getId());
            run.fail(cause.toString(), now());
            return runRepository.save(run);
        });
    }

    /** Must run inside the transaction that writes the run's result. */
    private void stillOurs(ReconciliationRun run) {
        LocalDateTime now = now();
        if (runRepository.heartbeat(run.getId(), now) == 0) {
            throw new RunTakenOverException(run);
        }
        run.heartbeat(now);
    }

    private void deleteStaged(UUID runId) {
        try {
            staging.delete(runId);
        } catch (RuntimeException e) {
            // the next run of any day deletes what runs that are over left behind
            log.warn("Could not delete the staged rows of run {}: {}", runId, e.toString());
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    /** The run's counts so far, summed over its chunks. */
    private static final class Totals {
        private int checked;
        private int matched;
        private int pending;
        private int payoutsChecked;
        private int payoutsMatched;
        private int payoutsPending;
        private int discrepancies;
        private final List<String> examples = new ArrayList<>();

        void add(Records.Result result) {
            checked += result.checked();
            matched += result.matched();
            pending += result.pending();
            payoutsChecked += result.payoutsChecked();
            payoutsMatched += result.payoutsMatched();
            payoutsPending += result.payoutsPending();
            discrepancies += result.discrepancies().size();
            result.discrepancies().stream().limit(LOGGED_DISCREPANCIES - examples.size())
                    .forEach(d -> examples.add(d.type() + " " + (d.paymentId() != null ? d.paymentId() : d.payoutId())));
        }
    }

    /**
     * Says the run is alive as it works, a few times within abandonedAfter; called after every
     * page and chunk, it writes only when the last time is old enough. Stops the run if another
     * attempt has taken its day over.
     */
    private final class Pulse {
        private final ReconciliationRun run;
        private final Runnable onHeartbeat;
        private final Duration every = properties.abandonedAfter().dividedBy(10);
        private LocalDateTime last;

        Pulse(ReconciliationRun run, Runnable onHeartbeat) {
            this.run = run;
            this.onHeartbeat = onHeartbeat;
            this.last = run.lastSeenAt();
        }

        void beat() {
            LocalDateTime now = now();
            if (Duration.between(last, now).compareTo(every) < 0) {
                return;
            }
            Integer alive = transactionTemplate.execute(status -> runRepository.heartbeat(run.getId(), now));
            if (alive == null || alive == 0) {
                throw new RunTakenOverException(run);
            }
            run.heartbeat(now);
            last = now;
            onHeartbeat.run();
        }
    }
}
