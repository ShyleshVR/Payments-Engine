package com.shylesh.reconciliation_service.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** What each system says, normalised for matching (all times UTC). */
public final class Records {

    private Records() {
    }

    /**
     * The processor's authorization of a payment (settlement report line).
     *
     * @param amount what was charged once captured (the captured amount), otherwise what is reserved
     */
    public record Authorization(String id, UUID paymentId, String status, BigDecimal amount, String currency,
                                BigDecimal refundedAmount, LocalDateTime createdAt, LocalDateTime capturedAt,
                                LocalDateTime voidedAt) {

        public boolean captured() {
            return capturedAt != null;
        }
    }

    /** A refund the processor made. */
    public record Refund(String id, UUID paymentId, BigDecimal amount, String currency, LocalDateTime createdAt) {
    }

    /**
     * A ledger transaction: a payment's (SETTLEMENT, REFUND_HOLD, REFUND_HOLD_RELEASE, REFUND) or a
     * payout's (PAYOUT_HOLD, PAYOUT_HOLD_RELEASE, PAYOUT, PAYOUT_RETURN).
     */
    public record LedgerTransaction(UUID id, UUID paymentId, UUID payoutId, UUID sagaId, String type, BigDecimal amount,
                                    String currency, LocalDateTime createdAt) {

        /** A payment's transaction. */
        public LedgerTransaction(UUID id, UUID paymentId, UUID sagaId, String type, BigDecimal amount, String currency,
                                 LocalDateTime createdAt) {
            this(id, paymentId, null, sagaId, type, amount, currency, createdAt);
        }
    }

    /** A payout transfer at the bank (settlement report line). */
    public record Transfer(String id, UUID payoutId, String status, BigDecimal amount, String currency, String failureCode,
                           LocalDateTime createdAt, LocalDateTime paidAt, LocalDateTime failedAt, LocalDateTime returnedAt) {

        public boolean paid() {
            return paidAt != null;
        }

        public boolean returned() {
            return returnedAt != null;
        }
    }

    /**
     * payout-service's view of a payout.
     *
     * @param inFlight its money movements may still be incomplete (the saga is still working on it)
     */
    public record Payout(UUID payoutId, String status, BigDecimal amount, String currency, boolean inFlight) {
    }

    /** payment-service's view of a payment. */
    public record Payment(UUID paymentId, String status, BigDecimal amount, String currency, boolean sagaActive,
                          boolean processorBacked) {
    }

    /**
     * Everything fetched for one business day: records in the matching window around the day, the
     * payments and payouts they concern, and refund and payout holds that stayed open too long.
     */
    public record Snapshot(LocalDateTime dayStart, LocalDateTime dayEnd, LocalDateTime now,
                           List<Authorization> authorizations, List<Refund> refunds,
                           List<LedgerTransaction> ledgerTransactions, List<LedgerTransaction> openRefundHolds,
                           Map<UUID, Payment> payments,
                           List<Transfer> transfers, List<LedgerTransaction> openPayoutHolds, Map<UUID, Payout> payouts) {

        /** Payments only. */
        public Snapshot(LocalDateTime dayStart, LocalDateTime dayEnd, LocalDateTime now, List<Authorization> authorizations,
                        List<Refund> refunds, List<LedgerTransaction> ledgerTransactions,
                        List<LedgerTransaction> openRefundHolds, Map<UUID, Payment> payments) {
            this(dayStart, dayEnd, now, authorizations, refunds, ledgerTransactions, openRefundHolds, payments,
                    List.of(), List.of(), Map.of());
        }
    }

    /** A disagreement about a payment (paymentId) or a payout (payoutId). */
    public record Discrepancy(DiscrepancyType type, UUID paymentId, UUID payoutId, BigDecimal processorAmount,
                              BigDecimal ledgerAmount, String paymentStatus, String detail) {

        /** About a payment. */
        public Discrepancy(DiscrepancyType type, UUID paymentId, BigDecimal processorAmount, BigDecimal ledgerAmount,
                           String paymentStatus, String detail) {
            this(type, paymentId, null, processorAmount, ledgerAmount, paymentStatus, detail);
        }
    }

    /**
     * @param checked        payments with a money movement on the day
     * @param matched        checked payments on which all three systems agree
     * @param pending        checked payments skipped because a saga is still running for them
     * @param payoutsChecked payouts with a money movement on the day (and the same for matched and pending)
     */
    public record Result(int checked, int matched, int pending, int payoutsChecked, int payoutsMatched, int payoutsPending,
                         List<Discrepancy> discrepancies) {

        public Result(int checked, int matched, int pending, List<Discrepancy> discrepancies) {
            this(checked, matched, pending, 0, 0, 0, discrepancies);
        }
    }
}
