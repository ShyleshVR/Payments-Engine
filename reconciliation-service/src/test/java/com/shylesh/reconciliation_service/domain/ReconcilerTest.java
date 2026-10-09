package com.shylesh.reconciliation_service.domain;

import com.shylesh.reconciliation_service.domain.Records.Authorization;
import com.shylesh.reconciliation_service.domain.Records.Discrepancy;
import com.shylesh.reconciliation_service.domain.Records.LedgerTransaction;
import com.shylesh.reconciliation_service.domain.Records.Payment;
import com.shylesh.reconciliation_service.domain.Records.Refund;
import com.shylesh.reconciliation_service.domain.Records.Result;
import com.shylesh.reconciliation_service.domain.Records.Snapshot;

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

/** Every discrepancy type, the matching window around midnight, and in-flight payments. */
class ReconcilerTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 8);
    private static final LocalDateTime START = DAY.atStartOfDay();
    private static final LocalDateTime NOON = START.plusHours(12);
    private static final LocalDateTime NOW = START.plusDays(1).plusHours(2);

    private final Reconciler reconciler = new Reconciler(new Reconciler.Settings(Duration.ofHours(1), Duration.ofHours(1)));

    private final List<Authorization> authorizations = new ArrayList<>();
    private final List<Refund> refunds = new ArrayList<>();
    private final List<LedgerTransaction> ledger = new ArrayList<>();
    private final List<LedgerTransaction> openHolds = new ArrayList<>();
    private final Map<UUID, Payment> payments = new HashMap<>();

    private static BigDecimal amount(String value) {
        return new BigDecimal(value);
    }

    private UUID payment(String status, String amount) {
        UUID id = UUID.randomUUID();
        payments.put(id, new Payment(id, status, amount(amount), "USD", false, true));
        return id;
    }

    private void captured(UUID paymentId, String amount, LocalDateTime at) {
        authorizations.add(new Authorization("auth_" + UUID.randomUUID(), paymentId, "CAPTURED", amount(amount), "USD",
                BigDecimal.ZERO, at.minusSeconds(1), at, null));
    }

    private void settled(UUID paymentId, String amount, LocalDateTime at) {
        ledger.add(new LedgerTransaction(UUID.randomUUID(), paymentId, UUID.randomUUID(), "SETTLEMENT", amount(amount), "USD", at));
    }

    /** The normal case: captured and settled, same amount. */
    private UUID paid(String amount, LocalDateTime at) {
        UUID id = payment("SUCCESS", amount);
        captured(id, amount, at);
        settled(id, amount, at.plusSeconds(1));
        return id;
    }

    private Result run() {
        return reconciler.reconcile(new Snapshot(START, START.plusDays(1), NOW, authorizations, refunds, ledger, openHolds, payments));
    }

    private static List<DiscrepancyType> types(Result result) {
        return result.discrepancies().stream().map(Discrepancy::type).toList();
    }

    @Test
    void agreeingSystemsProduceNoDiscrepancies() {
        paid("25.00", NOON);
        UUID refunded = payment("REFUNDED", "40.00");
        captured(refunded, "40.00", NOON);
        settled(refunded, "40.00", NOON);
        refunds.add(new Refund("re_1", refunded, amount("40.00"), "USD", NOON.plusHours(1)));
        ledger.add(new LedgerTransaction(UUID.randomUUID(), refunded, UUID.randomUUID(), "REFUND", amount("40.00"), "USD", NOON.plusHours(1)));
        UUID declined = payment("FAILED", "10.00");
        authorizations.add(new Authorization("auth_v", declined, "VOIDED", amount("10.00"), "USD", BigDecimal.ZERO, NOON, null, NOON));

        Result result = run();

        assertThat(result.discrepancies()).isEmpty();
        assertThat(result.checked()).isEqualTo(3);
        assertThat(result.matched()).isEqualTo(3);
    }

    @Test
    void captureWithoutSettlement() {
        UUID id = payment("SUCCESS", "25.00");
        captured(id, "25.00", NOON);

        assertThat(types(run())).containsExactly(DiscrepancyType.CAPTURED_NOT_SETTLED);
    }

    @Test
    void settlementWithoutCaptureIsAlsoAStatusContradiction() {
        UUID id = payment("SUCCESS", "25.00");
        settled(id, "25.00", NOON);

        assertThat(types(run())).containsExactlyInAnyOrder(DiscrepancyType.SETTLED_NOT_CAPTURED, DiscrepancyType.STATUS_MISMATCH);
    }

    @Test
    void settlementAmountDiffers() {
        UUID id = payment("SUCCESS", "25.00");
        captured(id, "25.00", NOON);
        settled(id, "25.01", NOON);

        Result result = run();

        assertThat(types(result)).containsExactly(DiscrepancyType.SETTLEMENT_AMOUNT_MISMATCH);
        assertThat(result.discrepancies().getFirst().processorAmount()).isEqualByComparingTo("25.00");
        assertThat(result.discrepancies().getFirst().ledgerAmount()).isEqualByComparingTo("25.01");
    }

    @Test
    void sameAmountWithDifferentScaleMatches() {
        UUID id = payment("SUCCESS", "25.00");
        captured(id, "25.0000", NOON);
        settled(id, "25", NOON);

        assertThat(run().discrepancies()).isEmpty();
    }

    @Test
    void refundOnOneSideOnly() {
        UUID notBooked = paid("30.00", NOON);
        refunds.add(new Refund("re_1", notBooked, amount("30.00"), "USD", NOON.plusHours(1)));
        UUID notRefunded = paid("20.00", NOON);
        ledger.add(new LedgerTransaction(UUID.randomUUID(), notRefunded, UUID.randomUUID(), "REFUND", amount("20.00"), "USD", NOON));

        Result result = run();

        assertThat(result.discrepancies()).filteredOn(d -> d.paymentId().equals(notBooked)).extracting(Discrepancy::type)
                .contains(DiscrepancyType.REFUNDED_NOT_BOOKED);
        assertThat(result.discrepancies()).filteredOn(d -> d.paymentId().equals(notRefunded)).extracting(Discrepancy::type)
                .contains(DiscrepancyType.BOOKED_NOT_REFUNDED);
    }

    @Test
    void refundTotalsDiffer() {
        UUID id = payment("SUCCESS", "50.00");
        captured(id, "50.00", NOON);
        settled(id, "50.00", NOON);
        refunds.add(new Refund("re_1", id, amount("20.00"), "USD", NOON));
        ledger.add(new LedgerTransaction(UUID.randomUUID(), id, UUID.randomUUID(), "REFUND", amount("25.00"), "USD", NOON));

        assertThat(types(run())).containsExactly(DiscrepancyType.REFUND_AMOUNT_MISMATCH);
    }

    @Test
    void failedPaymentThatWasCapturedIsAStatusMismatch() {
        UUID id = payment("FAILED", "25.00");
        captured(id, "25.00", NOON);
        settled(id, "25.00", NOON);

        assertThat(types(run())).containsExactly(DiscrepancyType.STATUS_MISMATCH);
    }

    @Test
    void refundedPaymentWithoutAFullProcessorRefundIsAStatusMismatch() {
        UUID id = payment("REFUNDED", "50.00");
        captured(id, "50.00", NOON);
        settled(id, "50.00", NOON);

        assertThat(types(run())).containsExactly(DiscrepancyType.STATUS_MISMATCH);
    }

    @Test
    void authorizationLeftOpenOnAFailedPayment() {
        UUID id = payment("CANCELLED", "25.00");
        authorizations.add(new Authorization("auth_open", id, "AUTHORIZED", amount("25.00"), "USD", BigDecimal.ZERO, NOON, null, null));

        Result result = run();

        assertThat(types(result)).containsExactly(DiscrepancyType.AUTHORIZATION_NOT_RELEASED);
        assertThat(result.discrepancies().getFirst().detail()).contains("auth_open");
    }

    @Test
    void duplicateSettlement() {
        UUID id = paid("25.00", NOON);
        settled(id, "25.00", NOON.plusMinutes(5));

        assertThat(types(run())).containsExactly(DiscrepancyType.DUPLICATE);
    }

    @Test
    void paymentsFromBeforeTheProcessorIntegrationAreOutOfScope() {
        UUID legacy = UUID.randomUUID();
        payments.put(legacy, new Payment(legacy, "SUCCESS", amount("25.00"), "USD", false, false));
        settled(legacy, "25.00", NOON);
        paid("10.00", NOON);

        Result result = run();

        assertThat(result.discrepancies()).isEmpty();
        assertThat(result.checked()).isEqualTo(1);
        assertThat(result.matched()).isEqualTo(1);
    }

    @Test
    void moneyForAnUnknownPayment() {
        UUID ghost = UUID.randomUUID();
        captured(ghost, "25.00", NOON);

        assertThat(types(run())).containsExactly(DiscrepancyType.UNKNOWN_PAYMENT);
    }

    @Test
    void staleRefundHoldsAreReportedFreshOnesAreNot() {
        UUID id = paid("25.00", NOON);
        openHolds.add(new LedgerTransaction(UUID.randomUUID(), id, UUID.randomUUID(), "REFUND_HOLD", amount("25.00"), "USD", NOW.minusHours(3)));
        openHolds.add(new LedgerTransaction(UUID.randomUUID(), id, UUID.randomUUID(), "REFUND_HOLD", amount("5.00"), "USD", NOW.minusMinutes(10)));

        assertThat(types(run())).containsExactly(DiscrepancyType.REFUND_HOLD_STALE);
    }

    @Test
    void paymentsWithARunningSagaArePendingNotDiscrepancies() {
        UUID id = UUID.randomUUID();
        payments.put(id, new Payment(id, "PROCESSING", amount("25.00"), "USD", true, true));
        captured(id, "25.00", NOON);

        Result result = run();

        assertThat(result.discrepancies()).isEmpty();
        assertThat(result.pending()).isEqualTo(1);
        assertThat(result.matched()).isZero();
    }

    @Test
    void captureJustBeforeMidnightMatchesASettlementJustAfter() {
        UUID id = payment("SUCCESS", "25.00");
        captured(id, "25.00", START.plusDays(1).minusSeconds(1));
        settled(id, "25.00", START.plusDays(1).plusSeconds(1));

        Result result = run();

        assertThat(result.discrepancies()).isEmpty();
        assertThat(result.matched()).isEqualTo(1);
    }

    @Test
    void factsFromAnotherDayAreNotCheckedToday() {
        UUID yesterday = payment("SUCCESS", "25.00");
        captured(yesterday, "25.00", START.minusMinutes(30));

        Result result = run();

        assertThat(result.checked()).isZero();
        assertThat(result.discrepancies()).isEmpty();
    }
}
