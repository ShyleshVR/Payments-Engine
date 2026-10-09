package com.shylesh.reconciliation_service.service;

import com.shylesh.reconciliation_service.client.SourceClient;
import com.shylesh.reconciliation_service.config.ReconciliationProperties;
import com.shylesh.reconciliation_service.domain.Reconciler;
import com.shylesh.reconciliation_service.domain.Records;
import com.shylesh.reconciliation_service.persistence.ReconciliationDiscrepancy;
import com.shylesh.reconciliation_service.persistence.ReconciliationDiscrepancyRepository;
import com.shylesh.reconciliation_service.persistence.ReconciliationRun;
import com.shylesh.reconciliation_service.persistence.ReconciliationRunRepository;
import com.shylesh.reconciliation_service.persistence.RunTrigger;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Reconciles one business day: reads the processor, the ledger and payment-service, compares
 * them (Reconciler), and stores the run with its discrepancies. A source that can't be read
 * fails the run (recorded, retried by the hourly catch-up) rather than producing a partial
 * result that would look like discrepancies.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReconciliationService {

    private final SourceClient sources;
    private final Reconciler reconciler;
    private final ReconciliationRunRepository runRepository;
    private final ReconciliationDiscrepancyRepository discrepancyRepository;
    private final ReconciliationMetrics metrics;
    private final ReconciliationProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    public ReconciliationRun run(LocalDate businessDate, RunTrigger trigger) {
        ReconciliationRun run;
        try {
            run = transactionTemplate.execute(status ->
                    runRepository.saveAndFlush(ReconciliationRun.start(businessDate, trigger, now())));
        } catch (DataIntegrityViolationException e) {
            throw new RunInProgressException(businessDate);
        }

        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            Records.Result result = reconciler.reconcile(snapshot(businessDate));
            transactionTemplate.executeWithoutResult(status -> {
                discrepancyRepository.saveAll(result.discrepancies().stream().map(d -> ReconciliationDiscrepancy.builder()
                        .runId(run.getId())
                        .type(d.type())
                        .paymentId(d.paymentId())
                        .processorAmount(d.processorAmount())
                        .ledgerAmount(d.ledgerAmount())
                        .paymentStatus(d.paymentStatus())
                        .detail(d.detail())
                        .build()).toList());
                run.complete(result, now());
                runRepository.save(run);
            });
            log.info("Reconciled {} ({}): checked {}, matched {}, pending {}, discrepancies {}",
                    businessDate, trigger, result.checked(), result.matched(), result.pending(), result.discrepancies().size());
            if (!result.discrepancies().isEmpty()) {
                log.warn("Reconciliation of {} found discrepancies: {}", businessDate,
                        result.discrepancies().stream().map(d -> d.type() + " " + d.paymentId()).toList());
            }
        } catch (RuntimeException e) {
            log.error("Reconciliation of {} failed: {}", businessDate, e.toString(), e);
            transactionTemplate.executeWithoutResult(status -> {
                run.fail(e.toString(), now());
                runRepository.save(run);
            });
        }
        sample.stop(Timer.builder("reconciliation.duration").tag("status", run.getStatus().name()).register(meterRegistry));
        Counter.builder("reconciliation.runs")
                .tag("status", run.getStatus().name())
                .tag("trigger", trigger.name())
                .register(meterRegistry).increment();
        metrics.refresh();
        return run;
    }

    /** The day's records, plus a margin either side so counterparts across midnight are found. */
    Records.Snapshot snapshot(LocalDate businessDate) {
        LocalDateTime dayStart = businessDate.atStartOfDay();
        LocalDateTime dayEnd = dayStart.plusDays(1);
        LocalDateTime from = dayStart.minus(properties.matchingMargin());
        LocalDateTime to = dayEnd.plus(properties.matchingMargin());
        LocalDateTime now = now();

        SourceClient.ProcessorRecords processor = sources.processorReport(from, to);
        List<Records.LedgerTransaction> ledger = sources.ledgerTransactions(from, to);
        List<Records.LedgerTransaction> openHolds = sources.openRefundHolds(now.minus(properties.refundHoldStaleAfter()));

        Set<UUID> paymentIds = new LinkedHashSet<>();
        Stream.of(processor.authorizations().stream().map(Records.Authorization::paymentId),
                        processor.refunds().stream().map(Records.Refund::paymentId),
                        ledger.stream().map(Records.LedgerTransaction::paymentId),
                        openHolds.stream().map(Records.LedgerTransaction::paymentId))
                .flatMap(s -> s)
                .filter(Objects::nonNull)
                .forEach(paymentIds::add);
        Map<UUID, Records.Payment> payments = sources.payments(paymentIds);

        return new Records.Snapshot(dayStart, dayEnd, now, processor.authorizations(), processor.refunds(),
                ledger, openHolds, payments);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
