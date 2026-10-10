package com.shylesh.reconciliation_service.domain;

import com.shylesh.reconciliation_service.domain.Records.Discrepancy;
import com.shylesh.reconciliation_service.domain.Records.LedgerTransaction;
import com.shylesh.reconciliation_service.domain.Records.Payout;
import com.shylesh.reconciliation_service.domain.Records.Result;
import com.shylesh.reconciliation_service.domain.Records.Snapshot;
import com.shylesh.reconciliation_service.domain.Records.Transfer;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The payout rules: bank transfers vs ledger payout postings vs payout-service. */
class PayoutReconcilerTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 8);
    private static final LocalDateTime START = DAY.atStartOfDay();
    private static final LocalDateTime NOON = START.plusHours(12);
    private static final LocalDateTime NOW = START.plusDays(1).plusHours(2);

    private final Reconciler reconciler = new Reconciler(
            new Reconciler.Settings(Duration.ofHours(1), Duration.ofHours(1), Duration.ofHours(2)));

    private final List<Transfer> transfers = new ArrayList<>();
    private final List<LedgerTransaction> ledger = new ArrayList<>();
    private final List<LedgerTransaction> openPayoutHolds = new ArrayList<>();
    private final Map<UUID, Payout> payouts = new HashMap<>();

    private static BigDecimal amount(String value) {
        return new BigDecimal(value);
    }

    private UUID payout(String status, String amount) {
        UUID id = UUID.randomUUID();
        payouts.put(id, new Payout(id, status, amount(amount), "USD", false));
        return id;
    }

    private void transfer(UUID payoutId, String amount, LocalDateTime paidAt, LocalDateTime failedAt, LocalDateTime returnedAt) {
        String status = returnedAt != null ? "RETURNED" : paidAt != null ? "PAID" : failedAt != null ? "FAILED" : "PENDING";
        transfers.add(new Transfer("tr_" + UUID.randomUUID(), payoutId, status, amount(amount), "USD", null,
                NOON.minusMinutes(5), paidAt, failedAt, returnedAt));
    }

    private void booked(UUID payoutId, String type, String amount, LocalDateTime at) {
        ledger.add(new LedgerTransaction(UUID.randomUUID(), null, payoutId, UUID.randomUUID(), type, amount(amount), "USD", at));
    }

    /** The normal case: paid by the bank and booked, same amount. */
    private UUID paid(String amount) {
        UUID id = payout("PAID", amount);
        transfer(id, amount, NOON, null, null);
        booked(id, "PAYOUT", amount, NOON.plusSeconds(5));
        return id;
    }

    private Result run() {
        return reconciler.reconcile(new Snapshot(START, START.plusDays(1), NOW, List.of(), List.of(), ledger, List.of(),
                Map.of(), transfers, openPayoutHolds, payouts));
    }

    private static List<DiscrepancyType> types(Result result) {
        return result.discrepancies().stream().map(Discrepancy::type).toList();
    }

    @Test
    void aPaidAndBookedPayoutMatches() {
        paid("50.00");

        Result result = run();

        assertThat(result.discrepancies()).isEmpty();
        assertThat(result.payoutsChecked()).isEqualTo(1);
        assertThat(result.payoutsMatched()).isEqualTo(1);
        assertThat(result.checked()).isZero();
    }

    @Test
    void aReturnedPayoutBookedBackMatches() {
        UUID id = payout("RETURNED", "50.00");
        transfer(id, "50.00", NOON, null, NOON.plusHours(2));
        booked(id, "PAYOUT", "50.00", NOON);
        booked(id, "PAYOUT_RETURN", "50.00", NOON.plusHours(2).plusMinutes(1));

        assertThat(run().discrepancies()).isEmpty();
    }

    @Test
    void aFailedTransferWithNothingBookedMatches() {
        UUID id = payout("FAILED", "50.00");
        transfer(id, "50.00", null, NOON, null);

        Result result = run();

        assertThat(result.discrepancies()).isEmpty();
        assertThat(result.payoutsMatched()).isEqualTo(1);
    }

    @Test
    void paidByTheBankButNeverBooked() {
        UUID id = payout("PAID", "50.00");
        transfer(id, "50.00", NOON, null, null);

        assertThat(types(run())).containsExactly(DiscrepancyType.PAID_NOT_BOOKED);
        assertThat(run().discrepancies().getFirst().payoutId()).isEqualTo(id);
        assertThat(run().discrepancies().getFirst().paymentId()).isNull();
    }

    @Test
    void bookedButNeverPaidByTheBank() {
        UUID id = payout("PAID", "50.00");
        transfer(id, "50.00", null, null, null);
        booked(id, "PAYOUT", "50.00", NOON);

        assertThat(types(run())).containsExactlyInAnyOrder(DiscrepancyType.BOOKED_NOT_PAID, DiscrepancyType.PAYOUT_STATUS_MISMATCH);
    }

    @Test
    void differentAmountsAtTheBankAndInTheLedger() {
        UUID id = payout("PAID", "50.00");
        transfer(id, "50.00", NOON, null, null);
        booked(id, "PAYOUT", "49.00", NOON);

        assertThat(types(run())).containsExactly(DiscrepancyType.PAYOUT_AMOUNT_MISMATCH);
    }

    @Test
    void anOldReturnNeverBookedIsADiscrepancyARecentOneIsPending() {
        UUID old = payout("PAID", "50.00");
        transfer(old, "50.00", NOON, null, NOON.plusHours(3));
        booked(old, "PAYOUT", "50.00", NOON);
        UUID recent = payout("PAID", "30.00");
        transfer(recent, "30.00", NOON, null, START.plusDays(1).minusMinutes(30));
        booked(recent, "PAYOUT", "30.00", NOON);

        // run at 02:00 the next day with a 3h grace: returns after 23:00 may not be noticed yet
        Result result = new Reconciler(new Reconciler.Settings(Duration.ofHours(1), Duration.ofHours(1), Duration.ofHours(3)))
                .reconcile(new Snapshot(START, START.plusDays(1), NOW, List.of(), List.of(), ledger, List.of(),
                        Map.of(), transfers, openPayoutHolds, payouts));

        assertThat(result.discrepancies()).extracting(Discrepancy::payoutId).containsOnly(old);
        assertThat(types(result)).containsExactlyInAnyOrder(DiscrepancyType.RETURN_NOT_BOOKED, DiscrepancyType.PAYOUT_STATUS_MISMATCH);
        assertThat(result.payoutsPending()).isEqualTo(1);
    }

    @Test
    void aReturnBookedThatTheBankNeverMade() {
        UUID id = payout("RETURNED", "50.00");
        transfer(id, "50.00", NOON, null, null);
        booked(id, "PAYOUT", "50.00", NOON);
        booked(id, "PAYOUT_RETURN", "50.00", NOON.plusHours(1));

        assertThat(types(run())).containsExactlyInAnyOrder(DiscrepancyType.RETURN_BOOKED_NOT_RETURNED,
                DiscrepancyType.PAYOUT_STATUS_MISMATCH);
    }

    @Test
    void aStatusThatContradictsTheMoney() {
        UUID id = payout("FAILED", "50.00");
        transfer(id, "50.00", NOON, null, null);
        booked(id, "PAYOUT", "50.00", NOON);

        assertThat(types(run())).containsExactly(DiscrepancyType.PAYOUT_STATUS_MISMATCH);
    }

    @Test
    void aPayoutStillInFlightIsPending() {
        UUID id = UUID.randomUUID();
        payouts.put(id, new Payout(id, "IN_TRANSIT", amount("50.00"), "USD", true));
        transfer(id, "50.00", NOON, null, null);

        Result result = run();

        assertThat(result.discrepancies()).isEmpty();
        assertThat(result.payoutsPending()).isEqualTo(1);
    }

    @Test
    void moneyForAPayoutNobodyKnows() {
        transfer(UUID.randomUUID(), "50.00", NOON, null, null);

        assertThat(types(run())).containsExactly(DiscrepancyType.UNKNOWN_PAYOUT);
    }

    @Test
    void twoPayoutPostingsForOnePayout() {
        UUID id = paid("50.00");
        booked(id, "PAYOUT", "50.00", NOON.plusMinutes(1));

        assertThat(types(run())).containsExactly(DiscrepancyType.DUPLICATE);
    }

    @Test
    void aStalePayoutHoldIsReported() {
        UUID id = payout("IN_TRANSIT", "50.00");
        openPayoutHolds.add(new LedgerTransaction(UUID.randomUUID(), null, id, UUID.randomUUID(), "PAYOUT_HOLD",
                amount("50.00"), "USD", NOW.minusDays(5)));

        Result result = run();

        assertThat(types(result)).containsExactly(DiscrepancyType.PAYOUT_HOLD_STALE);
        assertThat(result.discrepancies().getFirst().payoutId()).isEqualTo(id);
    }

    @Test
    void anotherDaysPayoutIsLeftAlone() {
        UUID yesterday = payout("PAID", "50.00");
        transfers.add(new Transfer("tr_y", yesterday, "PAID", amount("50.00"), "USD", null,
                START.minusHours(5), START.minusHours(4), null, null));

        Result result = run();

        assertThat(result.payoutsChecked()).isZero();
        assertThat(result.discrepancies()).isEmpty();
    }
}
