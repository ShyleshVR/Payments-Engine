package com.shylesh.reconciliation_service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shylesh.reconciliation_service.persistence.ReconciliationRun;
import com.shylesh.reconciliation_service.persistence.ReconciliationDiscrepancyRepository;
import com.shylesh.reconciliation_service.persistence.ReconciliationRunRepository;
import com.shylesh.reconciliation_service.persistence.RunStatus;
import com.shylesh.reconciliation_service.persistence.RunTrigger;
import com.shylesh.reconciliation_service.service.DailyReconciliationJob;
import com.shylesh.reconciliation_service.service.ReconciliationService;

import io.micrometer.core.instrument.MeterRegistry;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The service end to end against a real Postgres: a stub HTTP server plays the token endpoint,
 * the processor, the ledger and payment-service. "Now" is fixed at 2026-10-09 03:00 UTC, so the
 * days up to 2026-10-08 are over and can be reconciled by the scheduler.
 */
@SpringBootTest(properties = {
        "management.tracing.enabled=false",
        "payflow.reconciliation.catch-up-initial-delay=PT1H",
        "payflow.reconciliation.cron=0 0 2 1 1 *"
})
@AutoConfigureMockMvc
@Testcontainers
class ReconciliationIntegrationTest {

    static final Instant NOW = Instant.parse("2026-10-09T03:00:00Z");

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
            // starts at NOW and keeps ticking, so runs finish in order
            return Clock.offset(Clock.systemUTC(), java.time.Duration.between(Instant.now(), NOW));
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ReconciliationService reconciliationService;

    @Autowired
    private DailyReconciliationJob job;

    @Autowired
    private ReconciliationRunRepository runRepository;

    @Autowired
    private ReconciliationDiscrepancyRepository discrepancyRepository;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private org.springframework.security.oauth2.client.OAuth2AuthorizedClientService authorizedClients;

    @BeforeEach
    void resetStub() {
        stub.reset();
        // forget the cached token, so each test sees the token request
        authorizedClients.removeAuthorizedClient("payflow", "reconciliation-service");
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }

    /** One business day with four payments: one clean, one never settled, one left authorized, one in flight. */
    private Map<String, String> aDayWithProblems() {
        String clean = id();
        String notSettled = id();
        String openAuth = id();
        String inFlight = id();
        stub.report = """
                {"authorizations":[
                  {"id":"auth_1","reference":"pay_%s","status":"CAPTURED","amount":25.00,"currency":"USD","capturedAmount":25.00,"refundedAmount":0,
                   "createdAt":"2026-10-08T10:00:00","capturedAt":"2026-10-08T10:00:01","voidedAt":null},
                  {"id":"auth_2","reference":"pay_%s","status":"CAPTURED","amount":40.00,"currency":"USD","capturedAmount":40.00,"refundedAmount":0,
                   "createdAt":"2026-10-08T11:00:00","capturedAt":"2026-10-08T11:00:01","voidedAt":null},
                  {"id":"auth_3","reference":"pay_%s","status":"AUTHORIZED","amount":15.00,"currency":"USD","capturedAmount":0,"refundedAmount":0,
                   "createdAt":"2026-10-08T12:00:00","capturedAt":null,"voidedAt":null},
                  {"id":"auth_4","reference":"pay_%s","status":"CAPTURED","amount":9.00,"currency":"USD","capturedAmount":9.00,"refundedAmount":0,
                   "createdAt":"2026-10-08T23:59:00","capturedAt":"2026-10-08T23:59:30","voidedAt":null}
                ],"refunds":[]}""".formatted(clean, notSettled, openAuth, inFlight);
        // two ledger pages, to exercise paging
        stub.ledgerPages = List.of(
                "{\"items\":[{\"transactionId\":\"" + id() + "\",\"paymentId\":\"" + clean + "\",\"sagaId\":\"" + id()
                        + "\",\"type\":\"SETTLEMENT\",\"amount\":25.0000,\"currency\":\"USD\",\"createdAt\":\"2026-10-08T10:00:02\"}],\"hasNext\":true}",
                "{\"items\":[],\"hasNext\":false}");
        stub.payments = """
                [{"paymentId":"pay_%s","status":"SUCCESS","amount":25.00,"currency":"USD","sagaActive":false,"processorBacked":true},
                 {"paymentId":"pay_%s","status":"SUCCESS","amount":40.00,"currency":"USD","sagaActive":false,"processorBacked":true},
                 {"paymentId":"pay_%s","status":"CANCELLED","amount":15.00,"currency":"USD","sagaActive":false,"processorBacked":true},
                 {"paymentId":"pay_%s","status":"PROCESSING","amount":9.00,"currency":"USD","sagaActive":true,"processorBacked":true}]
                """.formatted(clean, notSettled, openAuth, inFlight);
        return Map.of("clean", clean, "notSettled", notSettled, "openAuth", openAuth, "inFlight", inFlight);
    }

    private MvcResult trigger(String date, String scope) throws Exception {
        return mockMvc.perform(post("/api/v1/reconciliation/runs")
                        .with(jwt().authorities(new SimpleGrantedAuthority(scope)))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"date\":\"" + date + "\"}"))
                .andReturn();
    }

    @Test
    void manualRunFindsEachDiscrepancyThroughTheApis() throws Exception {
        Map<String, String> ids = aDayWithProblems();

        MvcResult result = trigger("2026-10-08", "SCOPE_reconciliation:admin");

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.at("/run/status").asText()).isEqualTo("COMPLETED");
        assertThat(body.at("/run/paymentsChecked").asInt()).isEqualTo(4);
        assertThat(body.at("/run/matched").asInt()).isEqualTo(1);
        assertThat(body.at("/run/pending").asInt()).isEqualTo(1);
        Map<String, String> found = new java.util.HashMap<>();
        body.get("discrepancies").forEach(d -> found.put(d.get("type").asText(), d.get("paymentId").asText()));
        assertThat(found).containsExactlyInAnyOrderEntriesOf(Map.of(
                "CAPTURED_NOT_SETTLED", "pay_" + ids.get("notSettled"),
                "AUTHORIZATION_NOT_RELEASED", "pay_" + ids.get("openAuth")));

        // how the sources were called
        assertThat(stub.requestsTo("/oauth2/token")).isNotEmpty();
        assertThat(stub.requestsTo("/oauth2/token").getFirst().body()).contains("grant_type=client_credentials");
        assertThat(stub.requestsTo("/v1/reports").getFirst().apiKey()).isEqualTo("processor-key");
        assertThat(stub.requestsTo("/v1/reports").getFirst().query()).contains("from=2026-10-07T23:00", "to=2026-10-09T01:00");
        assertThat(stub.requestsTo("/api/v1/ledger/transactions")).hasSize(2)
                .allSatisfy(r -> assertThat(r.authorization()).isEqualTo("Bearer service-token"));
        assertThat(stub.requestsTo("/api/v1/payments/lookup").getFirst().authorization()).isEqualTo("Bearer service-token");
    }

    @Test
    void metricsShowTheLatestCompletedRun() {
        aDayWithProblems();

        reconciliationService.run(LocalDate.of(2026, 10, 8), RunTrigger.MANUAL);

        assertThat(meterRegistry.get("reconciliation.discrepancies").tag("type", "CAPTURED_NOT_SETTLED").gauge().value()).isEqualTo(1.0);
        assertThat(meterRegistry.get("reconciliation.discrepancies").tag("type", "DUPLICATE").gauge().value()).isEqualTo(0.0);
        assertThat(meterRegistry.get("reconciliation.latest.pending").gauge().value()).isEqualTo(1.0);
        assertThat(meterRegistry.get("reconciliation.last.success.timestamp").gauge().value())
                .isBetween((double) NOW.getEpochSecond(), (double) NOW.getEpochSecond() + 120);
    }

    @Test
    void payoutsAreReconciledAgainstTheBankAndPayoutService() throws Exception {
        String paid = id();
        String returnedNotBooked = id();
        stub.report = """
                {"authorizations":[],"refunds":[],"transfers":[
                  {"id":"tr_1","reference":"po_%s","status":"PAID","amount":120.00,"currency":"USD","failureCode":null,
                   "createdAt":"2026-10-04T09:00:00","paidAt":"2026-10-04T09:00:20","failedAt":null,"returnedAt":null},
                  {"id":"tr_2","reference":"po_%s","status":"RETURNED","amount":30.00,"currency":"USD","failureCode":"account_frozen",
                   "createdAt":"2026-10-04T10:00:00","paidAt":"2026-10-04T10:00:20","failedAt":null,"returnedAt":"2026-10-04T15:00:00"}
                ]}""".formatted(paid, returnedNotBooked);
        stub.ledgerPages = List.of("{\"items\":["
                + "{\"transactionId\":\"" + id() + "\",\"paymentId\":null,\"payoutId\":\"" + paid + "\",\"sagaId\":\"" + id()
                + "\",\"type\":\"PAYOUT\",\"amount\":120.0000,\"currency\":\"USD\",\"createdAt\":\"2026-10-04T09:00:25\"},"
                + "{\"transactionId\":\"" + id() + "\",\"paymentId\":null,\"payoutId\":\"" + returnedNotBooked + "\",\"sagaId\":\"" + id()
                + "\",\"type\":\"PAYOUT\",\"amount\":30.0000,\"currency\":\"USD\",\"createdAt\":\"2026-10-04T10:00:25\"}"
                + "],\"hasNext\":false}");
        stub.payouts = """
                [{"payoutId":"po_%s","status":"PAID","amount":120.00,"currency":"USD","inFlight":false},
                 {"payoutId":"po_%s","status":"PAID","amount":30.00,"currency":"USD","inFlight":false}]
                """.formatted(paid, returnedNotBooked);

        MvcResult result = trigger("2026-10-04", "SCOPE_reconciliation:admin");

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.at("/run/payoutsChecked").asInt()).isEqualTo(2);
        assertThat(body.at("/run/payoutsMatched").asInt()).isEqualTo(1);
        assertThat(body.at("/run/paymentsChecked").asInt()).isZero();
        Map<String, String> found = new java.util.HashMap<>();
        body.get("discrepancies").forEach(d -> {
            assertThat(d.get("paymentId").isNull()).isTrue();
            found.put(d.get("type").asText(), d.get("payoutId").asText());
        });
        assertThat(found).containsExactlyInAnyOrderEntriesOf(Map.of(
                "RETURN_NOT_BOOKED", "po_" + returnedNotBooked,
                "PAYOUT_STATUS_MISMATCH", "po_" + returnedNotBooked));
        assertThat(stub.requestsTo("/api/v1/payouts/lookup").getFirst().authorization()).isEqualTo("Bearer service-token");
        assertThat(stub.requestsTo("/api/v1/ledger/payout-holds/open")).isNotEmpty();
    }

    @Test
    void aDayKeepsAlertingUntilItIsReRunCleanWhateverDaysRunAfterIt() {
        LocalDate badDay = LocalDate.of(2026, 10, 8);
        aDayWithProblems();
        reconciliationService.run(badDay, RunTrigger.MANUAL);

        // another day reconciled later, clean: the bad day's discrepancies still stand
        stub.reset();
        reconciliationService.run(LocalDate.of(2026, 10, 7), RunTrigger.MANUAL);
        assertThat(meterRegistry.get("reconciliation.discrepancies").tag("type", "CAPTURED_NOT_SETTLED").gauge().value()).isEqualTo(1.0);

        // the bad day re-run after a fix: cleared
        reconciliationService.run(badDay, RunTrigger.MANUAL);
        assertThat(meterRegistry.get("reconciliation.discrepancies").tag("type", "CAPTURED_NOT_SETTLED").gauge().value()).isEqualTo(0.0);
        assertThat(meterRegistry.get("reconciliation.discrepancies").tag("type", "AUTHORIZATION_NOT_RELEASED").gauge().value()).isEqualTo(0.0);
    }

    @Test
    void aCaptureIsComparedByTheAmountActuallyCaptured() {
        String partial = id();
        stub.report = """
                {"authorizations":[
                  {"id":"auth_p","reference":"pay_%s","status":"CAPTURED","amount":25.00,"currency":"USD","capturedAmount":20.00,"refundedAmount":0,
                   "createdAt":"2026-10-05T10:00:00","capturedAt":"2026-10-05T10:00:01","voidedAt":null}
                ],"refunds":[]}""".formatted(partial);
        stub.ledgerPages = List.of("{\"items\":[{\"transactionId\":\"" + id() + "\",\"paymentId\":\"" + partial + "\",\"sagaId\":\"" + id()
                + "\",\"type\":\"SETTLEMENT\",\"amount\":20.00,\"currency\":\"USD\",\"createdAt\":\"2026-10-05T10:00:02\"}],\"hasNext\":false}");
        stub.payments = """
                [{"paymentId":"pay_%s","status":"SUCCESS","amount":25.00,"currency":"USD","sagaActive":false,"processorBacked":true}]
                """.formatted(partial);

        ReconciliationRun run = reconciliationService.run(LocalDate.of(2026, 10, 5), RunTrigger.MANUAL);

        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(run.getMatched()).isEqualTo(1);
        assertThat(run.getDiscrepancyCount()).isZero();
    }

    @Test
    void runCountersExistFromStartupSoTheFirstFailureAlerts() {
        // a counter series that first appears at 1 makes Prometheus' increase() miss that run
        assertThat(meterRegistry.find("reconciliation.runs").tag("status", "FAILED").tag("trigger", "SCHEDULED").counter()).isNotNull();
        assertThat(meterRegistry.find("reconciliation.runs").tag("status", "FAILED").tag("trigger", "MANUAL").counter()).isNotNull();
    }

    @Test
    void anUnreadableSourceFailsTheRunInsteadOfReportingDiscrepancies() {
        aDayWithProblems();
        stub.ledgerDown = true;

        ReconciliationRun run = reconciliationService.run(LocalDate.of(2026, 9, 30), RunTrigger.MANUAL);

        assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(run.getError()).contains("503");
        assertThat(run.getDiscrepancyCount()).isNull();
    }

    @Test
    void schedulerReconcilesEachFinishedDayOnceEvenWithTwoReplicasAtOnce() throws Exception {
        // runs left by other tests would make the scheduler skip their days
        discrepancyRepository.deleteAll();
        runRepository.deleteAll();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Void>> replicas = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                replicas.add(() -> {
                    job.catchUp();
                    return null;
                });
            }
            for (var future : pool.invokeAll(replicas)) {
                future.get();
            }
        } finally {
            pool.shutdownNow();
        }
        job.catchUp();
        job.reconcileYesterday();

        Map<LocalDate, Long> scheduledRuns = runRepository.findAll().stream()
                .filter(r -> r.getTrigger() == RunTrigger.SCHEDULED)
                .collect(Collectors.groupingBy(ReconciliationRun::getBusinessDate, Collectors.counting()));
        assertThat(scheduledRuns).containsOnlyKeys(LocalDate.of(2026, 10, 6), LocalDate.of(2026, 10, 7), LocalDate.of(2026, 10, 8));
        assertThat(scheduledRuns.values()).containsOnly(1L);
    }

    @Test
    void apiNeedsTheReconciliationScopeAndAPastDate() throws Exception {
        int noToken = mockMvc.perform(get("/api/v1/reconciliation/runs")).andReturn().getResponse().getStatus();
        int wrongScope = trigger("2026-10-08", "SCOPE_ledger:admin").getResponse().getStatus();
        int future = trigger("2026-10-10", "SCOPE_reconciliation:admin").getResponse().getStatus();
        int listed = mockMvc.perform(get("/api/v1/reconciliation/runs").param("date", "2026-10-08")
                .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_reconciliation:admin")))).andReturn().getResponse().getStatus();

        assertThat(noToken).isEqualTo(401);
        assertThat(wrongScope).isEqualTo(403);
        assertThat(future).isEqualTo(400);
        assertThat(listed).isEqualTo(200);
    }

    @Test
    void aDayAlreadyBeingReconciledIsAConflict() throws Exception {
        runRepository.saveAndFlush(ReconciliationRun.start(LocalDate.of(2026, 9, 20), RunTrigger.SCHEDULED,
                java.time.LocalDateTime.ofInstant(NOW, ZoneOffset.UTC)));

        assertThat(trigger("2026-09-20", "SCOPE_reconciliation:admin").getResponse().getStatus()).isEqualTo(409);
    }
}
