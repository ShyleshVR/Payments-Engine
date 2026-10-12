package com.shylesh.reconciliation_service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shylesh.reconciliation_service.domain.DiscrepancyType;
import com.shylesh.reconciliation_service.domain.Records;
import com.shylesh.reconciliation_service.persistence.ReconciliationDiscrepancy;
import com.shylesh.reconciliation_service.persistence.ReconciliationDiscrepancyRepository;
import com.shylesh.reconciliation_service.persistence.ReconciliationRun;
import com.shylesh.reconciliation_service.persistence.ReconciliationRunRepository;
import com.shylesh.reconciliation_service.persistence.RunStatus;
import com.shylesh.reconciliation_service.persistence.RunTrigger;
import com.shylesh.reconciliation_service.persistence.StagingStore;
import com.shylesh.reconciliation_service.service.ReconciliationService;
import com.shylesh.reconciliation_service.service.RunInProgressException;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A day that doesn't fit in one page or one chunk: the sources are read 3 records a page and
 * reconciled 2 payments at a time, against a real Postgres (the staging table) and the stub
 * sources. Also what happens to a run's staged rows, and to a run that stopped reporting.
 */
@SpringBootTest(properties = {
        "management.tracing.enabled=false",
        "payflow.reconciliation.catch-up-initial-delay=PT1H",
        "payflow.reconciliation.cron=0 0 2 1 1 *",
        "payflow.reconciliation.page-size=3",
        "payflow.reconciliation.chunk-size=2"
})
@Testcontainers
class ChunkedReconciliationIntegrationTest {

    static final Instant NOW = Instant.parse("2026-10-09T03:00:00Z");
    static final LocalDate DAY = LocalDate.of(2026, 10, 6);

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    static StubSources stub;

    @BeforeAll
    static void startStub() throws IOException {
        stub = new StubSources();
    }

    @AfterAll
    static void stopStub() {
        stub.stop();
    }

    @DynamicPropertySource
    static void sources(DynamicPropertyRegistry registry) {
        registry.add("payflow.reconciliation.processor.base-url", () -> stub.baseUrl());
        registry.add("payflow.reconciliation.processor.api-key", () -> "processor-key");
        registry.add("payflow.reconciliation.ledger.base-url", () -> stub.baseUrl());
        registry.add("payflow.reconciliation.payments.base-url", () -> stub.baseUrl());
        registry.add("payflow.reconciliation.payouts.base-url", () -> stub.baseUrl());
        registry.add("spring.security.oauth2.client.provider.payflow.token-uri", () -> stub.baseUrl() + "/oauth2/token");
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> stub.baseUrl() + "/oauth2/jwks");
    }

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock testClock() {
            return Clock.offset(Clock.systemUTC(), Duration.between(Instant.now(), NOW));
        }
    }

    @Autowired
    private ReconciliationService reconciliationService;

    @Autowired
    private ReconciliationRunRepository runRepository;

    @Autowired
    private ReconciliationDiscrepancyRepository discrepancyRepository;

    @Autowired
    private StagingStore staging;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void reset() {
        stub.reset();
        jdbcTemplate.update("DELETE FROM reconciliation_staging");
        discrepancyRepository.deleteAll();
        runRepository.deleteAll();
    }

    // ---- the day's data, as the sources would serve it ----

    private final List<String> authorizations = new ArrayList<>();
    private final List<String> refunds = new ArrayList<>();
    private final List<String> transfers = new ArrayList<>();
    private final List<String> ledger = new ArrayList<>();
    private final List<String> payments = new ArrayList<>();
    private final List<String> payouts = new ArrayList<>();
    private int sequence;

    private static UUID id() {
        return UUID.randomUUID();
    }

    private String captured(UUID paymentId, String amount, String createdAt, String capturedAt) {
        String id = "auth_" + ++sequence;
        authorizations.add("""
                {"id":"%s","reference":"pay_%s","status":"CAPTURED","amount":%s,"currency":"USD","capturedAmount":%s,
                 "refundedAmount":0,"createdAt":"%s","capturedAt":"%s","voidedAt":null}""".formatted(id, paymentId, amount, amount, createdAt, capturedAt));
        return id;
    }

    private void booked(UUID paymentId, UUID payoutId, String type, String amount, String createdAt) {
        ledger.add("""
                {"transactionId":"%s","paymentId":%s,"payoutId":%s,"sagaId":"%s","type":"%s","amount":%s,"currency":"USD","createdAt":"%s"}"""
                .formatted(id(), quoted(paymentId), quoted(payoutId), id(), type, amount, createdAt));
    }

    private void payment(UUID paymentId, String status, String amount, boolean sagaActive) {
        payments.add("""
                {"paymentId":"pay_%s","status":"%s","amount":%s,"currency":"USD","sagaActive":%s,"processorBacked":true}"""
                .formatted(paymentId, status, amount, sagaActive));
    }

    private static String quoted(UUID id) {
        return id == null ? "null" : "\"" + id + "\"";
    }

    private void serve() {
        stub.authorizations = "[" + String.join(",", authorizations) + "]";
        stub.refunds = "[" + String.join(",", refunds) + "]";
        stub.transfers = "[" + String.join(",", transfers) + "]";
        stub.ledger = "[" + String.join(",", ledger) + "]";
        stub.payments = "[" + String.join(",", payments) + "]";
        stub.payouts = "[" + String.join(",", payouts) + "]";
    }

    /** What the day below must come to, whatever the page and chunk sizes. */
    private record Expected(Map<DiscrepancyType, Set<UUID>> discrepancies, Set<UUID> stagedPayments, UUID heldPayment) {
    }

    /**
     * 28 payments (5 clean, 5 captured but not settled, 5 still in flight, 5 refunded in full,
     * 5 settled for another amount, 1 captured twice, 1 settled after midnight, 1 unknown to
     * payment-service), an authorization that isn't ours, 5 payouts (one returned but not booked)
     * and two stale holds.
     */
    private Expected aBusyDay() {
        Map<DiscrepancyType, Set<UUID>> expected = new HashMap<>();
        Set<UUID> staged = new HashSet<>();
        for (int i = 0; i < 25; i++) {
            UUID paymentId = id();
            staged.add(paymentId);
            String minute = "%02d".formatted(i);
            String created = "2026-10-06T08:" + minute + ":00";
            String capturedAt = "2026-10-06T08:" + minute + ":01";
            String settledAt = "2026-10-06T08:" + minute + ":02";
            switch (i % 5) {
                case 0 -> {
                    captured(paymentId, "25.00", created, capturedAt);
                    booked(paymentId, null, "SETTLEMENT", "25.0000", settledAt);
                    payment(paymentId, "SUCCESS", "25.00", false);
                }
                case 1 -> {
                    captured(paymentId, "40.00", created, capturedAt);
                    payment(paymentId, "SUCCESS", "40.00", false);
                    expected.computeIfAbsent(DiscrepancyType.CAPTURED_NOT_SETTLED, t -> new HashSet<>()).add(paymentId);
                }
                case 2 -> {
                    captured(paymentId, "9.00", created, capturedAt);
                    payment(paymentId, "PROCESSING", "9.00", true);
                }
                case 3 -> {
                    String authorizationId = captured(paymentId, "60.00", created, capturedAt);
                    booked(paymentId, null, "SETTLEMENT", "60.0000", settledAt);
                    // refunded hours later: the refund and its bookings are on other pages than the capture
                    refunds.add("""
                            {"id":"re_%d","authorizationId":"%s","reference":"pay_%s","amount":60.00,"currency":"USD","createdAt":"2026-10-06T15:%s:00"}"""
                            .formatted(i, authorizationId, paymentId, minute));
                    booked(paymentId, null, "REFUND_HOLD", "60.0000", "2026-10-06T15:" + minute + ":00");
                    booked(paymentId, null, "REFUND", "60.0000", "2026-10-06T15:" + minute + ":05");
                    payment(paymentId, "REFUNDED", "60.00", false);
                }
                default -> {
                    captured(paymentId, "30.00", created, capturedAt);
                    booked(paymentId, null, "SETTLEMENT", "29.0000", settledAt);
                    payment(paymentId, "SUCCESS", "30.00", false);
                    expected.computeIfAbsent(DiscrepancyType.SETTLEMENT_AMOUNT_MISMATCH, t -> new HashSet<>()).add(paymentId);
                }
            }
        }

        // captured twice, twelve hours apart: its two authorizations can't be on the same page
        UUID twice = id();
        staged.add(twice);
        captured(twice, "70.00", "2026-10-06T07:00:00", "2026-10-06T07:00:01");
        captured(twice, "70.00", "2026-10-06T19:00:00", "2026-10-06T19:00:01");
        booked(twice, null, "SETTLEMENT", "70.0000", "2026-10-06T07:00:02");
        payment(twice, "SUCCESS", "70.00", false);
        expected.put(DiscrepancyType.DUPLICATE, Set.of(twice));

        // captured just before midnight, settled just after: matched through the margin
        UUID late = id();
        staged.add(late);
        captured(late, "12.00", "2026-10-06T23:59:00", "2026-10-06T23:59:30");
        booked(late, null, "SETTLEMENT", "12.0000", "2026-10-07T00:00:10");
        payment(late, "SUCCESS", "12.00", false);

        // money moved for a payment payment-service has never heard of
        UUID unknown = id();
        staged.add(unknown);
        captured(unknown, "33.00", "2026-10-06T12:00:00", "2026-10-06T12:00:01");
        booked(unknown, null, "SETTLEMENT", "33.0000", "2026-10-06T12:00:02");
        expected.put(DiscrepancyType.UNKNOWN_PAYMENT, Set.of(unknown));

        // someone else's authorization: no payment to match it to, so it is left out
        authorizations.add("""
                {"id":"auth_foreign","reference":"order-17","status":"CAPTURED","amount":5.00,"currency":"USD","capturedAmount":5.00,
                 "refundedAmount":0,"createdAt":"2026-10-06T09:30:00","capturedAt":"2026-10-06T09:30:01","voidedAt":null}""");

        for (int i = 0; i < 5; i++) {
            UUID payoutId = id();
            boolean returned = i == 4;
            transfers.add("""
                    {"id":"tr_%d","reference":"po_%s","status":"%s","amount":120.00,"currency":"USD","failureCode":%s,
                     "createdAt":"2026-10-06T09:0%d:00","paidAt":"2026-10-06T09:0%d:20","failedAt":null,"returnedAt":%s}"""
                    .formatted(i, payoutId, returned ? "RETURNED" : "PAID", returned ? "\"account_frozen\"" : "null", i, i,
                            returned ? "\"2026-10-06T15:00:00\"" : "null"));
            booked(null, payoutId, "PAYOUT", "120.0000", "2026-10-06T09:0" + i + ":25");
            payouts.add("""
                    {"payoutId":"po_%s","status":"PAID","amount":120.00,"currency":"USD","inFlight":false}""".formatted(payoutId));
            if (returned) {
                expected.put(DiscrepancyType.RETURN_NOT_BOOKED, Set.of(payoutId));
                expected.put(DiscrepancyType.PAYOUT_STATUS_MISMATCH, Set.of(payoutId));
            }
        }

        // holds from other days that nobody released
        UUID heldPayment = id();
        payment(heldPayment, "REFUND_PENDING", "18.00", false);
        stub.openHolds = """
                [{"transactionId":"%s","paymentId":"%s","payoutId":null,"sagaId":"%s","type":"REFUND_HOLD","amount":18.0000,
                  "currency":"USD","createdAt":"2026-10-02T08:00:00"}]""".formatted(id(), heldPayment, id());
        UUID heldPayout = id();
        payouts.add("""
                {"payoutId":"po_%s","status":"PROCESSING","amount":55.00,"currency":"USD","inFlight":true}""".formatted(heldPayout));
        stub.openPayoutHolds = """
                [{"transactionId":"%s","paymentId":null,"payoutId":"%s","sagaId":"%s","type":"PAYOUT_HOLD","amount":55.0000,
                  "currency":"USD","createdAt":"2026-10-01T08:00:00"}]""".formatted(id(), heldPayout, id());
        expected.put(DiscrepancyType.REFUND_HOLD_STALE, Set.of(heldPayment));
        expected.put(DiscrepancyType.PAYOUT_HOLD_STALE, Set.of(heldPayout));

        serve();
        return new Expected(expected, staged, heldPayment);
    }

    private Map<DiscrepancyType, Set<UUID>> found(ReconciliationRun run) {
        return discrepancyRepository.findByRunIdOrderByIdAsc(run.getId()).stream().collect(Collectors.groupingBy(
                ReconciliationDiscrepancy::getType,
                Collectors.mapping(d -> d.getPaymentId() != null ? d.getPaymentId() : d.getPayoutId(), Collectors.toSet())));
    }

    private static List<String> askedFor(String lookupBody) {
        try {
            List<String> ids = new ArrayList<>();
            new ObjectMapper().readTree(lookupBody).path("paymentIds").forEach(id -> ids.add(id.asText()));
            return ids;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private long stagedRows() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM reconciliation_staging", Long.class);
    }

    private static LocalDateTime now() {
        return LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
    }

    // ---- tests ----

    @Test
    void aDayOfManyPagesAndChunksIsReconciledAsOne() {
        Expected expected = aBusyDay();

        ReconciliationRun run = reconciliationService.run(DAY, RunTrigger.MANUAL);

        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(run.getPaymentsChecked()).isEqualTo(28);
        assertThat(run.getMatched()).isEqualTo(11);
        assertThat(run.getPending()).isEqualTo(5);
        assertThat(run.getPayoutsChecked()).isEqualTo(5);
        assertThat(run.getPayoutsMatched()).isEqualTo(4);
        assertThat(run.getPayoutsPending()).isZero();
        assertThat(found(run)).isEqualTo(expected.discrepancies());
        // one row each: nothing was reported by two chunks
        assertThat(run.getDiscrepancyCount()).isEqualTo(16);
        assertThat(discrepancyRepository.findByRunIdOrderByIdAsc(run.getId())).hasSize(16);

        // it really was read in pages of 3 and reconciled 2 payments at a time
        assertThat(stub.requestsTo("/v1/reports/settlement/authorizations")).hasSize(10);
        assertThat(stub.requestsTo("/api/v1/ledger/transactions")).hasSize(11);
        List<List<String>> lookups = stub.requestsTo("/api/v1/payments/lookup").stream().map(r -> askedFor(r.body())).toList();
        // 14 chunks of 2 payments, and the payment of the stale hold
        assertThat(lookups).hasSize(15).allSatisfy(ids -> assertThat(ids).hasSizeLessThanOrEqualTo(2));
        // every payment was looked up, so reconciled, in exactly one chunk
        Set<String> all = expected.stagedPayments().stream().map(id -> "pay_" + id).collect(Collectors.toSet());
        all.add("pay_" + expected.heldPayment());
        assertThat(lookups.stream().flatMap(List::stream).toList()).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(all);

        assertThat(stagedRows()).isZero();
    }

    @Test
    void aRunThatFailsHalfwayKeepsNoStagedRowsAndNoDiscrepancies() {
        aBusyDay();
        // the payments are reconciled (and their discrepancies stored) before the payouts fail
        stub.payoutsDown = true;

        ReconciliationRun run = reconciliationService.run(DAY, RunTrigger.MANUAL);

        assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(run.getError()).contains("503");
        assertThat(run.getDiscrepancyCount()).isNull();
        assertThat(stub.requestsTo("/api/v1/payments/lookup")).isNotEmpty();
        assertThat(discrepancyRepository.findByRunIdOrderByIdAsc(run.getId())).isEmpty();
        assertThat(stagedRows()).isZero();

        // and the day can be reconciled as soon as the source is back
        stub.payoutsDown = false;
        ReconciliationRun again = reconciliationService.run(DAY, RunTrigger.MANUAL);
        assertThat(again.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(again.getDiscrepancyCount()).isEqualTo(16);
    }

    @Test
    void aRunThatStoppedReportingIsTakenOver() {
        Expected expected = aBusyDay();
        // a run whose pod was killed ten minutes ago: still RUNNING, with rows staged and a discrepancy stored
        ReconciliationRun dead = runRepository.saveAndFlush(ReconciliationRun.builder()
                .id(id()).businessDate(DAY).trigger(RunTrigger.SCHEDULED).status(RunStatus.RUNNING)
                .startedAt(now().minusMinutes(20)).heartbeatAt(now().minusMinutes(10)).build());
        staging.addAuthorizations(dead.getId(), List.of(new Records.Authorization("auth_old", id(), "CAPTURED",
                new BigDecimal("5.00"), "USD", BigDecimal.ZERO, now().minusDays(3), now().minusDays(3), null)));
        discrepancyRepository.saveAndFlush(ReconciliationDiscrepancy.builder().runId(dead.getId())
                .type(DiscrepancyType.CAPTURED_NOT_SETTLED).paymentId(id()).detail("from the dead run").build());

        ReconciliationRun run = reconciliationService.run(DAY, RunTrigger.SCHEDULED);

        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(found(run)).isEqualTo(expected.discrepancies());
        ReconciliationRun abandoned = runRepository.findById(dead.getId()).orElseThrow();
        assertThat(abandoned.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(abandoned.getError()).startsWith("Abandoned");
        assertThat(abandoned.getFinishedAt()).isNotNull();
        assertThat(discrepancyRepository.findByRunIdOrderByIdAsc(dead.getId())).isEmpty();
        assertThat(stagedRows()).isZero();
    }

    @Test
    void aRunThatIsStillReportingKeepsItsDay() {
        aBusyDay();
        // started long ago (a big day), but heard from a minute ago
        ReconciliationRun alive = runRepository.saveAndFlush(ReconciliationRun.builder()
                .id(id()).businessDate(DAY).trigger(RunTrigger.SCHEDULED).status(RunStatus.RUNNING)
                .startedAt(now().minusMinutes(40)).heartbeatAt(now().minusMinutes(1)).build());
        staging.addAuthorizations(alive.getId(), List.of(new Records.Authorization("auth_live", id(), "CAPTURED",
                new BigDecimal("5.00"), "USD", BigDecimal.ZERO, now().minusDays(3), now().minusDays(3), null)));

        assertThatThrownBy(() -> reconciliationService.run(DAY, RunTrigger.MANUAL)).isInstanceOf(RunInProgressException.class);

        assertThat(runRepository.findById(alive.getId()).orElseThrow().getStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(staging.count(alive.getId())).isEqualTo(1);
        assertThat(runRepository.findAll()).hasSize(1);
    }

    @Test
    void aRunTakenOverWhileItWorksStopsWithoutWritingAResult() {
        aBusyDay();
        // while this run reads the ledger, another attempt decides it is dead and ends it
        stub.onLedgerRead = () -> jdbcTemplate.update(
                "UPDATE reconciliation_run SET status = 'FAILED', error = 'Abandoned: test', finished_at = started_at "
                        + "WHERE business_date = ? AND status = 'RUNNING'", DAY);

        ReconciliationRun run = reconciliationService.run(DAY, RunTrigger.MANUAL);

        assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(run.getError()).isEqualTo("Abandoned: test");
        assertThat(runRepository.findAll()).hasSize(1).allSatisfy(r -> assertThat(r.getStatus()).isEqualTo(RunStatus.FAILED));
        assertThat(runRepository.findAll().getFirst().getPaymentsChecked()).isNull();
        assertThat(discrepancyRepository.findAll()).isEmpty();
        assertThat(stagedRows()).isZero();
    }

    @Test
    void stagedRecordsComeBackUnchangedForARangeOfPaymentsOrPayouts() {
        UUID run = id();
        UUID otherRun = id();
        List<UUID> paymentIds = List.of(id(), id(), id());
        UUID payoutId = id();
        LocalDateTime at = LocalDateTime.parse("2026-10-06T10:15:30.123456");
        List<Records.Authorization> staged = new ArrayList<>();
        for (UUID paymentId : paymentIds) {
            staged.add(new Records.Authorization("auth_" + paymentId, paymentId, "CAPTURED", new BigDecimal("25.00"), "USD",
                    new BigDecimal("0"), at, at.plusSeconds(1), null));
        }
        // no payment: can't be matched, so it isn't kept
        staged.add(new Records.Authorization("auth_foreign", null, "CAPTURED", new BigDecimal("5.00"), "USD", BigDecimal.ZERO, at, at, null));
        Records.Refund refund = new Records.Refund("re_1", paymentIds.getFirst(), new BigDecimal("10.00"), "USD", at.plusHours(1));
        Records.LedgerTransaction settlement = new Records.LedgerTransaction(id(), paymentIds.getFirst(), null, id(), "SETTLEMENT",
                new BigDecimal("25.0000"), "USD", at.plusSeconds(2));
        Records.LedgerTransaction payout = new Records.LedgerTransaction(id(), null, payoutId, id(), "PAYOUT",
                new BigDecimal("120.0000"), "USD", at);
        Records.Transfer transfer = new Records.Transfer("tr_1", payoutId, "PAID", new BigDecimal("120.00"), "USD", null, at,
                at.plusSeconds(20), null, null);
        staging.addAuthorizations(run, staged);
        staging.addRefunds(run, List.of(refund));
        staging.addLedgerTransactions(run, List.of(settlement, payout));
        staging.addTransfers(run, List.of(transfer));
        staging.addAuthorizations(otherRun, List.of(new Records.Authorization("auth_other", id(), "CAPTURED",
                new BigDecimal("1.00"), "USD", BigDecimal.ZERO, at, at, null)));

        // the run's payments, two at a time, each once
        List<UUID> firstChunk = staging.paymentIdsAfter(run, null, 2);
        List<UUID> secondChunk = staging.paymentIdsAfter(run, firstChunk.getLast(), 2);
        assertThat(firstChunk).hasSize(2);
        assertThat(secondChunk).hasSize(1);
        assertThat(staging.paymentIdsAfter(run, secondChunk.getLast(), 2)).isEmpty();
        List<UUID> listed = new ArrayList<>(firstChunk);
        listed.addAll(secondChunk);
        assertThat(listed).containsExactlyInAnyOrderElementsOf(paymentIds);

        // a chunk's records: only those payments', only this run's, equal to what went in
        StagingStore.Staged all = staging.ofPayments(run, firstChunk.getFirst(), secondChunk.getLast());
        assertThat(all.authorizations()).containsExactlyElementsOf(staged.subList(0, 3));
        assertThat(all.refunds()).containsExactly(refund);
        assertThat(all.ledgerTransactions()).containsExactly(settlement);
        assertThat(all.transfers()).isEmpty();
        StagingStore.Staged first = staging.ofPayments(run, firstChunk.getFirst(), firstChunk.getLast());
        assertThat(first.authorizations()).extracting(Records.Authorization::paymentId).containsExactlyInAnyOrderElementsOf(firstChunk);

        assertThat(staging.payoutIdsAfter(run, null, 2)).containsExactly(payoutId);
        StagingStore.Staged ofPayout = staging.ofPayouts(run, payoutId, payoutId);
        assertThat(ofPayout.transfers()).containsExactly(transfer);
        assertThat(ofPayout.ledgerTransactions()).containsExactly(payout);
        assertThat(ofPayout.authorizations()).isEmpty();

        // deleting a run's rows leaves the other run's
        assertThat(staging.delete(run)).isEqualTo(7);
        assertThat(staging.count(otherRun)).isEqualTo(1);
    }

    @Test
    void aRunDeletesWhatEndedRunsLeftBehindAndLeavesARunAtWorkAlone() {
        // another day is being reconciled right now, and yesterday's failed run could not clean up
        ReconciliationRun working = runRepository.saveAndFlush(ReconciliationRun.start(DAY.minusDays(2), RunTrigger.MANUAL, now()));
        ReconciliationRun over = ReconciliationRun.start(DAY.minusDays(1), RunTrigger.MANUAL, now().minusHours(20));
        over.fail("source down", now().minusHours(20));
        runRepository.saveAndFlush(over);
        Records.Authorization payment = new Records.Authorization("auth_x", id(), "CAPTURED", new BigDecimal("1.00"), "USD",
                BigDecimal.ZERO, now(), now(), null);
        Records.Transfer payout = new Records.Transfer("tr_x", id(), "PAID", new BigDecimal("1.00"), "USD", null, now(), now(), null, null);
        staging.addAuthorizations(working.getId(), List.of(payment));
        staging.addTransfers(working.getId(), List.of(payout));
        staging.addAuthorizations(over.getId(), List.of(payment));
        staging.addTransfers(over.getId(), List.of(payout));

        ReconciliationRun run = reconciliationService.run(DAY, RunTrigger.MANUAL);

        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(staging.count(over.getId())).isZero();
        assertThat(staging.count(working.getId())).isEqualTo(2);
        assertThat(stagedRows()).isEqualTo(2);
    }
}
