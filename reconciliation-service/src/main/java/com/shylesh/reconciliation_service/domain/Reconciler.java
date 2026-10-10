package com.shylesh.reconciliation_service.domain;

import com.shylesh.reconciliation_service.domain.Records.Authorization;
import com.shylesh.reconciliation_service.domain.Records.Discrepancy;
import com.shylesh.reconciliation_service.domain.Records.LedgerTransaction;
import com.shylesh.reconciliation_service.domain.Records.Payment;
import com.shylesh.reconciliation_service.domain.Records.Refund;
import com.shylesh.reconciliation_service.domain.Records.Result;
import com.shylesh.reconciliation_service.domain.Records.Snapshot;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Three-way reconciliation of one business day: the processor (what money actually did), the
 * ledger (what we booked) and payment-service (what we told the merchant); payouts are checked
 * the same way against the bank and payout-service (PayoutReconciler). A pure function, so every
 * rule is tested directly.
 *
 * Checks are per fact: a capture, settlement or refund that happened on the day is checked
 * against its counterpart anywhere in the snapshot's matching window (the day plus a margin), so
 * a capture at 23:59 settled at 00:01 still matches, and every fact is checked on the day it
 * happened. Payments with a saga still running are skipped as pending: their money movements may
 * legitimately be incomplete.
 */
public class Reconciler {

    private static final String SETTLEMENT = "SETTLEMENT";
    private static final String REFUND = "REFUND";

    /**
     * @param authorizationGrace   how long an authorization of a failed or cancelled payment may stay
     *                             open (the saga releases it within seconds; this absorbs clock skew)
     * @param refundHoldStaleAfter how long a refund hold may stay open before it is reported
     * @param payoutReturnGrace    how long a returned payout may wait for its saga to notice
     */
    public record Settings(Duration authorizationGrace, Duration refundHoldStaleAfter, Duration payoutReturnGrace) {

        public Settings(Duration authorizationGrace, Duration refundHoldStaleAfter) {
            this(authorizationGrace, refundHoldStaleAfter, Duration.ofHours(2));
        }
    }

    private final Settings settings;
    private final PayoutReconciler payouts;

    public Reconciler(Settings settings) {
        this.settings = settings;
        this.payouts = new PayoutReconciler(new PayoutReconciler.Settings(settings.payoutReturnGrace()));
    }

    public Result reconcile(Snapshot snapshot) {
        Map<UUID, List<Authorization>> authorizations = group(snapshot.authorizations(), Authorization::paymentId);
        Map<UUID, List<Refund>> refunds = group(snapshot.refunds(), Refund::paymentId);
        Map<UUID, List<LedgerTransaction>> settlements = group(ofType(snapshot.ledgerTransactions(), SETTLEMENT), LedgerTransaction::paymentId);
        Map<UUID, List<LedgerTransaction>> ledgerRefunds = group(ofType(snapshot.ledgerTransactions(), REFUND), LedgerTransaction::paymentId);

        Set<UUID> involved = new LinkedHashSet<>();
        snapshot.authorizations().stream()
                .filter(a -> inDay(snapshot, a.createdAt()) || inDay(snapshot, a.capturedAt()) || inDay(snapshot, a.voidedAt()))
                .forEach(a -> involved.add(a.paymentId()));
        snapshot.refunds().stream().filter(r -> inDay(snapshot, r.createdAt())).forEach(r -> involved.add(r.paymentId()));
        snapshot.ledgerTransactions().stream()
                .filter(t -> (SETTLEMENT.equals(t.type()) || REFUND.equals(t.type())) && inDay(snapshot, t.createdAt()))
                .forEach(t -> involved.add(t.paymentId()));
        involved.remove(null);
        // Payments from before the processor integration have no processor record by design
        involved.removeIf(id -> snapshot.payments().containsKey(id) && !snapshot.payments().get(id).processorBacked());

        List<Discrepancy> discrepancies = new ArrayList<>();
        int matched = 0;
        int pending = 0;
        for (UUID paymentId : involved) {
            Payment payment = snapshot.payments().get(paymentId);
            if (payment != null && payment.sagaActive()) {
                pending++;
                continue;
            }
            List<Discrepancy> found = check(snapshot, paymentId, payment,
                    authorizations.getOrDefault(paymentId, List.of()), refunds.getOrDefault(paymentId, List.of()),
                    settlements.getOrDefault(paymentId, List.of()), ledgerRefunds.getOrDefault(paymentId, List.of()));
            if (found.isEmpty()) {
                matched++;
            }
            discrepancies.addAll(found);
        }

        LocalDateTime staleBefore = snapshot.now().minus(settings.refundHoldStaleAfter());
        for (LedgerTransaction hold : snapshot.openRefundHolds()) {
            if (hold.createdAt().isBefore(staleBefore)) {
                Payment payment = snapshot.payments().get(hold.paymentId());
                discrepancies.add(new Discrepancy(DiscrepancyType.REFUND_HOLD_STALE, hold.paymentId(), null, hold.amount(),
                        payment == null ? null : payment.status(),
                        "Refund hold " + hold.id() + " open since " + hold.createdAt()));
            }
        }
        PayoutReconciler.Outcome payoutOutcome = payouts.reconcile(snapshot);
        discrepancies.addAll(payoutOutcome.discrepancies());
        return new Result(involved.size(), matched, pending,
                payoutOutcome.checked(), payoutOutcome.matched(), payoutOutcome.pending(), discrepancies);
    }

    private List<Discrepancy> check(Snapshot snapshot, UUID paymentId, Payment payment, List<Authorization> authorizations,
                                    List<Refund> refunds, List<LedgerTransaction> settlements, List<LedgerTransaction> ledgerRefunds) {
        List<Discrepancy> found = new ArrayList<>();
        String status = payment == null ? null : payment.status();
        List<Authorization> captured = authorizations.stream().filter(Authorization::captured).toList();
        Authorization capture = captured.isEmpty() ? null : captured.getFirst();
        LedgerTransaction settlement = settlements.isEmpty() ? null : settlements.getFirst();
        BigDecimal processorRefunded = sum(refunds.stream().map(Refund::amount).toList());
        BigDecimal ledgerRefunded = sum(ledgerRefunds.stream().map(LedgerTransaction::amount).toList());

        if (payment == null) {
            found.add(new Discrepancy(DiscrepancyType.UNKNOWN_PAYMENT, paymentId,
                    capture == null ? null : capture.amount(), settlement == null ? null : settlement.amount(), null,
                    "Money moved for a payment payment-service doesn't know"));
            return found;
        }

        if (captured.size() > 1) {
            found.add(discrepancy(DiscrepancyType.DUPLICATE, paymentId, capture.amount(), null, status,
                    captured.size() + " captured authorizations"));
        }
        if (settlements.size() > 1) {
            found.add(discrepancy(DiscrepancyType.DUPLICATE, paymentId, null, settlement.amount(), status,
                    settlements.size() + " settlements"));
        }

        if (capture != null && inDay(snapshot, capture.capturedAt()) && settlement == null) {
            found.add(discrepancy(DiscrepancyType.CAPTURED_NOT_SETTLED, paymentId, capture.amount(), null, status,
                    "Captured " + capture.id() + " at " + capture.capturedAt() + ", no settlement in the ledger"));
        }
        if (settlement != null && inDay(snapshot, settlement.createdAt()) && capture == null) {
            found.add(discrepancy(DiscrepancyType.SETTLED_NOT_CAPTURED, paymentId, null, settlement.amount(), status,
                    "Settled at " + settlement.createdAt() + ", no capture at the processor"));
        }
        if (capture != null && settlement != null
                && (capture.amount().compareTo(settlement.amount()) != 0 || !Objects.equals(capture.currency(), settlement.currency()))) {
            found.add(discrepancy(DiscrepancyType.SETTLEMENT_AMOUNT_MISMATCH, paymentId, capture.amount(), settlement.amount(), status,
                    "Processor " + capture.amount().toPlainString() + " " + capture.currency()
                            + " vs ledger " + settlement.amount().toPlainString() + " " + settlement.currency()));
        }

        boolean refundOnDay = refunds.stream().anyMatch(r -> inDay(snapshot, r.createdAt()))
                || ledgerRefunds.stream().anyMatch(t -> inDay(snapshot, t.createdAt()));
        if (refundOnDay) {
            if (!refunds.isEmpty() && ledgerRefunds.isEmpty()) {
                found.add(discrepancy(DiscrepancyType.REFUNDED_NOT_BOOKED, paymentId, processorRefunded, null, status,
                        "Processor refunded " + processorRefunded.toPlainString() + ", nothing in the ledger"));
            } else if (refunds.isEmpty() && !ledgerRefunds.isEmpty()) {
                found.add(discrepancy(DiscrepancyType.BOOKED_NOT_REFUNDED, paymentId, null, ledgerRefunded, status,
                        "Ledger refunded " + ledgerRefunded.toPlainString() + ", no refund at the processor"));
            } else if (processorRefunded.compareTo(ledgerRefunded) != 0) {
                found.add(discrepancy(DiscrepancyType.REFUND_AMOUNT_MISMATCH, paymentId, processorRefunded, ledgerRefunded, status,
                        "Processor refunded " + processorRefunded.toPlainString() + ", ledger " + ledgerRefunded.toPlainString()));
            }
        }

        switch (status) {
            case "FAILED", "CANCELLED" -> {
                if (capture != null) {
                    found.add(discrepancy(DiscrepancyType.STATUS_MISMATCH, paymentId, capture.amount(),
                            settlement == null ? null : settlement.amount(), status,
                            "Payment is " + status + " but the processor captured " + capture.amount().toPlainString()));
                }
                LocalDateTime openTooLong = snapshot.now().minus(settings.authorizationGrace());
                authorizations.stream()
                        .filter(a -> "AUTHORIZED".equals(a.status()) && a.createdAt().isBefore(openTooLong))
                        .forEach(a -> found.add(discrepancy(DiscrepancyType.AUTHORIZATION_NOT_RELEASED, paymentId, a.amount(), null,
                                status, "Authorization " + a.id() + " still holds the customer's funds")));
            }
            case "SUCCESS" -> {
                if (capture == null && settlement != null) {
                    found.add(discrepancy(DiscrepancyType.STATUS_MISMATCH, paymentId, null, settlement.amount(), status,
                            "Payment is SUCCESS but the processor has no capture"));
                }
                if (capture != null && processorRefunded.compareTo(payment.amount()) >= 0) {
                    found.add(discrepancy(DiscrepancyType.STATUS_MISMATCH, paymentId, processorRefunded, ledgerRefunded, status,
                            "Payment is SUCCESS but the processor refunded it in full"));
                }
            }
            case "REFUNDED" -> {
                if (processorRefunded.compareTo(payment.amount()) != 0) {
                    found.add(discrepancy(DiscrepancyType.STATUS_MISMATCH, paymentId, processorRefunded, ledgerRefunded, status,
                            "Payment is REFUNDED but the processor refunded " + processorRefunded.toPlainString()
                                    + " of " + payment.amount().toPlainString()));
                }
            }
            default -> {
                // AUTHORIZED / PROCESSING / REFUND_PENDING without a running saga don't occur;
                // CREATED is a payment from before sagas, with no money movement to check
            }
        }
        return found;
    }

    private static Discrepancy discrepancy(DiscrepancyType type, UUID paymentId, BigDecimal processorAmount,
                                           BigDecimal ledgerAmount, String status, String detail) {
        return new Discrepancy(type, paymentId, processorAmount, ledgerAmount, status, detail);
    }

    private static boolean inDay(Snapshot snapshot, LocalDateTime time) {
        return time != null && !time.isBefore(snapshot.dayStart()) && time.isBefore(snapshot.dayEnd());
    }

    private static List<LedgerTransaction> ofType(List<LedgerTransaction> transactions, String type) {
        return transactions.stream().filter(t -> type.equals(t.type())).toList();
    }

    private static <T> Map<UUID, List<T>> group(Collection<T> items, Function<T, UUID> key) {
        return items.stream().filter(i -> key.apply(i) != null)
                .collect(Collectors.groupingBy(key, LinkedHashMap::new, Collectors.toList()));
    }

    private static BigDecimal sum(List<BigDecimal> amounts) {
        return amounts.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
