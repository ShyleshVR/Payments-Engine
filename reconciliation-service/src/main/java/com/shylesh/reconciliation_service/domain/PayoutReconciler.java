package com.shylesh.reconciliation_service.domain;

import com.shylesh.reconciliation_service.domain.Records.Discrepancy;
import com.shylesh.reconciliation_service.domain.Records.LedgerTransaction;
import com.shylesh.reconciliation_service.domain.Records.Payout;
import com.shylesh.reconciliation_service.domain.Records.Snapshot;
import com.shylesh.reconciliation_service.domain.Records.Transfer;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The payout side of the daily reconciliation: the bank (what money did), the ledger (what we
 * booked) and payout-service (what we told the merchant). Same rules as for payments: each fact
 * that happened on the day (a transfer paid or returned, a payout or return booked) is checked
 * against its counterpart in the matching window, and payouts whose saga is still working are
 * pending.
 */
public class PayoutReconciler {

    private static final String PAYOUT = "PAYOUT";
    private static final String PAYOUT_RETURN = "PAYOUT_RETURN";

    /**
     * @param returnGrace how long a return may be waiting to be noticed (the saga checks paid
     *                    transfers periodically); a younger unbooked return is pending, not a discrepancy
     */
    public record Settings(Duration returnGrace) {
    }

    /** Counts for the run, plus the discrepancies. */
    public record Outcome(int checked, int matched, int pending, List<Discrepancy> discrepancies) {
    }

    private final Settings settings;

    public PayoutReconciler(Settings settings) {
        this.settings = settings;
    }

    public Outcome reconcile(Snapshot snapshot) {
        Map<UUID, List<Transfer>> transfers = snapshot.transfers().stream().filter(t -> t.payoutId() != null)
                .collect(Collectors.groupingBy(Transfer::payoutId, LinkedHashMap::new, Collectors.toList()));
        Map<UUID, List<LedgerTransaction>> booked = byPayout(snapshot, PAYOUT);
        Map<UUID, List<LedgerTransaction>> returnsBooked = byPayout(snapshot, PAYOUT_RETURN);

        Set<UUID> involved = new LinkedHashSet<>();
        snapshot.transfers().stream()
                .filter(t -> inDay(snapshot, t.createdAt()) || inDay(snapshot, t.paidAt())
                        || inDay(snapshot, t.failedAt()) || inDay(snapshot, t.returnedAt()))
                .forEach(t -> involved.add(t.payoutId()));
        snapshot.ledgerTransactions().stream()
                .filter(t -> t.payoutId() != null && (PAYOUT.equals(t.type()) || PAYOUT_RETURN.equals(t.type()))
                        && inDay(snapshot, t.createdAt()))
                .forEach(t -> involved.add(t.payoutId()));
        involved.remove(null);

        List<Discrepancy> discrepancies = new ArrayList<>();
        int matched = 0;
        int pending = 0;
        LocalDateTime returnsNoticedBy = snapshot.now().minus(settings.returnGrace());
        for (UUID payoutId : involved) {
            Payout payout = snapshot.payouts().get(payoutId);
            List<Transfer> payoutTransfers = transfers.getOrDefault(payoutId, List.of());
            Transfer transfer = payoutTransfers.isEmpty() ? null : payoutTransfers.getFirst();
            boolean returnNotNoticedYet = transfer != null && transfer.returned() && transfer.returnedAt().isAfter(returnsNoticedBy)
                    && returnsBooked.getOrDefault(payoutId, List.of()).isEmpty();
            if (payout != null && (payout.inFlight() || returnNotNoticedYet)) {
                pending++;
                continue;
            }
            List<Discrepancy> found = check(snapshot, payoutId, payout, payoutTransfers,
                    booked.getOrDefault(payoutId, List.of()), returnsBooked.getOrDefault(payoutId, List.of()));
            if (found.isEmpty()) {
                matched++;
            }
            discrepancies.addAll(found);
        }

        for (LedgerTransaction hold : snapshot.openPayoutHolds()) {
            Payout payout = snapshot.payouts().get(hold.payoutId());
            discrepancies.add(discrepancy(DiscrepancyType.PAYOUT_HOLD_STALE, hold.payoutId(), null, hold.amount(),
                    payout == null ? null : payout.status(), "Payout hold " + hold.id() + " open since " + hold.createdAt()));
        }
        return new Outcome(involved.size(), matched, pending, discrepancies);
    }

    private List<Discrepancy> check(Snapshot snapshot, UUID payoutId, Payout payout, List<Transfer> transfers,
                                    List<LedgerTransaction> booked, List<LedgerTransaction> returnsBooked) {
        List<Discrepancy> found = new ArrayList<>();
        Transfer transfer = transfers.isEmpty() ? null : transfers.getFirst();
        LedgerTransaction posting = booked.isEmpty() ? null : booked.getFirst();
        BigDecimal bankAmount = transfer == null ? null : transfer.amount();
        BigDecimal ledgerAmount = posting == null ? null : posting.amount();

        if (payout == null) {
            found.add(discrepancy(DiscrepancyType.UNKNOWN_PAYOUT, payoutId, bankAmount, ledgerAmount, null,
                    "Money moved for a payout payout-service doesn't know"));
            return found;
        }
        String status = payout.status();
        boolean paid = transfer != null && transfer.paid();
        boolean returned = transfer != null && transfer.returned();

        if (transfers.size() > 1) {
            found.add(discrepancy(DiscrepancyType.DUPLICATE, payoutId, bankAmount, null, status, transfers.size() + " transfers"));
        }
        if (booked.size() > 1) {
            found.add(discrepancy(DiscrepancyType.DUPLICATE, payoutId, null, ledgerAmount, status, booked.size() + " payout postings"));
        }

        if (paid && inDay(snapshot, transfer.paidAt()) && posting == null) {
            found.add(discrepancy(DiscrepancyType.PAID_NOT_BOOKED, payoutId, bankAmount, null, status,
                    "Transfer " + transfer.id() + " paid at " + transfer.paidAt() + ", no payout in the ledger"));
        }
        if (posting != null && inDay(snapshot, posting.createdAt()) && !paid) {
            found.add(discrepancy(DiscrepancyType.BOOKED_NOT_PAID, payoutId, bankAmount, ledgerAmount, status,
                    "Payout booked at " + posting.createdAt() + ", the bank never paid it"));
        }
        if (paid && posting != null
                && (bankAmount.compareTo(ledgerAmount) != 0 || !Objects.equals(transfer.currency(), posting.currency()))) {
            found.add(discrepancy(DiscrepancyType.PAYOUT_AMOUNT_MISMATCH, payoutId, bankAmount, ledgerAmount, status,
                    "Bank " + bankAmount.toPlainString() + " " + transfer.currency() + " vs ledger "
                            + ledgerAmount.toPlainString() + " " + posting.currency()));
        }
        if (returned && inDay(snapshot, transfer.returnedAt()) && returnsBooked.isEmpty()) {
            found.add(discrepancy(DiscrepancyType.RETURN_NOT_BOOKED, payoutId, bankAmount, null, status,
                    "Transfer " + transfer.id() + " returned at " + transfer.returnedAt() + ", no return in the ledger"));
        }
        if (!returnsBooked.isEmpty() && inDay(snapshot, returnsBooked.getFirst().createdAt()) && !returned) {
            found.add(discrepancy(DiscrepancyType.RETURN_BOOKED_NOT_RETURNED, payoutId, bankAmount,
                    returnsBooked.getFirst().amount(), status, "Return booked, the bank never returned the transfer"));
        }

        // a payout no saga is working on any more must say what money did
        String expected = returned ? "RETURNED" : paid ? "PAID" : "FAILED";
        if (!expected.equals(status)) {
            found.add(discrepancy(DiscrepancyType.PAYOUT_STATUS_MISMATCH, payoutId, bankAmount, ledgerAmount, status,
                    "Payout is " + status + " but the bank's transfer is " + (transfer == null ? "missing" : transfer.status())));
        }
        return found;
    }

    private static Map<UUID, List<LedgerTransaction>> byPayout(Snapshot snapshot, String type) {
        return snapshot.ledgerTransactions().stream()
                .filter(t -> t.payoutId() != null && type.equals(t.type()))
                .collect(Collectors.groupingBy(LedgerTransaction::payoutId, LinkedHashMap::new, Collectors.toList()));
    }

    private static Discrepancy discrepancy(DiscrepancyType type, UUID payoutId, BigDecimal bankAmount,
                                           BigDecimal ledgerAmount, String status, String detail) {
        return new Discrepancy(type, null, payoutId, bankAmount, ledgerAmount, status, detail);
    }

    private static boolean inDay(Snapshot snapshot, LocalDateTime time) {
        return time != null && !time.isBefore(snapshot.dayStart()) && time.isBefore(snapshot.dayEnd());
    }
}
