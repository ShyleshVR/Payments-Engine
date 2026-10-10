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

    /** matched/pending: of the payments checked; the payout counts are separate. */
    public record RunSummary(UUID runId, LocalDate businessDate, String trigger, String status,
                             LocalDateTime startedAt, LocalDateTime finishedAt, Integer paymentsChecked,
                             Integer matched, Integer pending, Integer payoutsChecked, Integer payoutsMatched,
                             Integer payoutsPending, Integer discrepancyCount, String error) {

        static RunSummary of(ReconciliationRun run) {
            return new RunSummary(run.getId(), run.getBusinessDate(), run.getTrigger().name(), run.getStatus().name(),
                    run.getStartedAt(), run.getFinishedAt(), run.getPaymentsChecked(), run.getMatched(),
                    run.getPending(), run.getPayoutsChecked(), run.getPayoutsMatched(), run.getPayoutsPending(),
                    run.getDiscrepancyCount(), run.getError());
        }
    }

    /**
     * paymentId ("pay_") or payoutId ("po_"): what the discrepancy is about. processorAmount is
     * the bank's for a payout; paymentStatus is the payout's status for a payout.
     */
    public record DiscrepancyView(String type, String paymentId, String payoutId, BigDecimal processorAmount,
                                  BigDecimal ledgerAmount, String paymentStatus, String detail) {

        static DiscrepancyView of(ReconciliationDiscrepancy d) {
            return new DiscrepancyView(d.getType().name(), d.getPaymentId() == null ? null : "pay_" + d.getPaymentId(),
                    d.getPayoutId() == null ? null : "po_" + d.getPayoutId(),
                    d.getProcessorAmount(), d.getLedgerAmount(), d.getPaymentStatus(), d.getDetail());
        }
    }

    public record RunDetail(RunSummary run, List<DiscrepancyView> discrepancies) {
    }

    /** @param date the business day (UTC) to reconcile */
    public record TriggerRequest(@NotNull LocalDate date) {
    }
}
