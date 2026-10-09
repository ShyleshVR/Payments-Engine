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

    /** A ledger transaction (type SETTLEMENT, REFUND_HOLD, REFUND_HOLD_RELEASE or REFUND). */
    public record LedgerTransaction(UUID id, UUID paymentId, UUID sagaId, String type, BigDecimal amount,
                                    String currency, LocalDateTime createdAt) {
    }

    /** payment-service's view of a payment. */
    public record Payment(UUID paymentId, String status, BigDecimal amount, String currency, boolean sagaActive,
                          boolean processorBacked) {
    }

    /**
     * Everything fetched for one business day: records in the matching window around the day, the
     * payments they concern, and refund holds still open.
     */
    public record Snapshot(LocalDateTime dayStart, LocalDateTime dayEnd, LocalDateTime now,
                           List<Authorization> authorizations, List<Refund> refunds,
                           List<LedgerTransaction> ledgerTransactions, List<LedgerTransaction> openRefundHolds,
                           Map<UUID, Payment> payments) {
    }

    public record Discrepancy(DiscrepancyType type, UUID paymentId, BigDecimal processorAmount,
                              BigDecimal ledgerAmount, String paymentStatus, String detail) {
    }

    /**
     * @param checked payments with a money movement on the day
     * @param matched checked payments on which all three systems agree
     * @param pending checked payments skipped because a saga is still running for them
     */
    public record Result(int checked, int matched, int pending, List<Discrepancy> discrepancies) {
    }
}
