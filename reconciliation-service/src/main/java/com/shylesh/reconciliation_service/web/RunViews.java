package com.shylesh.reconciliation_service.web;

import com.shylesh.reconciliation_service.persistence.ReconciliationDiscrepancy;
import com.shylesh.reconciliation_service.persistence.ReconciliationRun;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** API shapes of runs and discrepancies. */
public final class RunViews {

    private RunViews() {
    }

    public record RunSummary(UUID runId, LocalDate businessDate, String trigger, String status,
                             LocalDateTime startedAt, LocalDateTime finishedAt, Integer paymentsChecked,
                             Integer matched, Integer pending, Integer discrepancyCount, String error) {

        static RunSummary of(ReconciliationRun run) {
            return new RunSummary(run.getId(), run.getBusinessDate(), run.getTrigger().name(), run.getStatus().name(),
                    run.getStartedAt(), run.getFinishedAt(), run.getPaymentsChecked(), run.getMatched(),
                    run.getPending(), run.getDiscrepancyCount(), run.getError());
        }
    }

    public record DiscrepancyView(String type, String paymentId, BigDecimal processorAmount, BigDecimal ledgerAmount,
                                  String paymentStatus, String detail) {

        static DiscrepancyView of(ReconciliationDiscrepancy d) {
            return new DiscrepancyView(d.getType().name(), d.getPaymentId() == null ? null : "pay_" + d.getPaymentId(),
                    d.getProcessorAmount(), d.getLedgerAmount(), d.getPaymentStatus(), d.getDetail());
        }
    }

    public record RunDetail(RunSummary run, List<DiscrepancyView> discrepancies) {
    }

    /** @param date the business day (UTC) to reconcile */
    public record TriggerRequest(@NotNull LocalDate date) {
    }
}
