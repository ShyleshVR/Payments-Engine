package com.shylesh.payout_service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shylesh.payout_service.batch.DailyPayoutJob;
import com.shylesh.payout_service.batch.PayoutBatch;
import com.shylesh.payout_service.batch.PayoutBatchService;
import com.shylesh.payout_service.outbox.OutboxEvent;
import com.shylesh.payout_service.outbox.OutboxEventRepository;
import com.shylesh.payout_service.payout.Payout;
import com.shylesh.payout_service.payout.PayoutRepository;
import com.shylesh.payout_service.payout.PayoutStatus;
import com.shylesh.payout_service.payout.PayoutTrigger;
import com.shylesh.payout_service.saga.PayoutSaga;
import com.shylesh.payout_service.saga.PayoutSagaRepository;
import com.shylesh.payout_service.saga.SagaState;

import io.micrometer.core.instrument.MeterRegistry;

import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Payouts end to end against real Postgres and Kafka, with a stub ledger (consuming
 * ledger-commands, replying on payout-ledger-replies) and a stub bank / ledger API / token
 * endpoint. Bank and saga timings are shrunk to milliseconds.
 */
@SpringBootTest(properties = {
        "management.tracing.enabled=false",
        "payflow.payouts.bank.api-key=test-key",
        "payflow.payouts.saga.poll-interval=100ms",
        "payflow.payouts.saga.transit-poll-interval=150ms",
        "payflow.payouts.saga.return-window=1500ms",
        "payflow.payouts.saga.return-check-interval=150ms",
        "payflow.payouts.saga.submit-timeout=2s",
        "payflow.payouts.saga.in-transit-timeout=2s",
        "payflow.payouts.saga.retry.initial-backoff=100ms",
        "payflow.payouts.saga.retry.max-backoff=300ms",
        "payflow.payouts.saga.replies.timeout=2s",
        "payflow.payouts.batch.cron=-",
        "payflow.payouts.batch.catch-up-initial-delay=PT1H",
        "outbox.publisher.poll-interval=PT0.1S"
})
@AutoConfigureMockMvc
@Testcontainers
class PayoutIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:4.2.0");

    static StubServer stub;
    static StubLedger ledger;

    @TestConfiguration
    static class Topics {
        /** Created by ledger-service in a real deployment. */
        @Bean
        NewTopic payoutLedgerReplies() {
            return new NewTopic("payout-ledger-replies", 3, (short) 1);
        }
    }

    @BeforeAll
    static void startStubs() throws IOException {
        stub = new StubServer();
    }

    @DynamicPropertySource
    static void endpoints(DynamicPropertyRegistry registry) {
        registry.add("payflow.payouts.bank.base-url", () -> stub.baseUrl());
        registry.add("payflow.payouts.ledger.base-url", () -> stub.baseUrl());
        registry.add("spring.security.oauth2.client.provider.payflow.token-uri", () -> stub.baseUrl() + "/oauth2/token");
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> stub.baseUrl() + "/oauth2/jwks");
    }

    @AfterAll
    static void stopStubs() {
        stub.stop();
        if (ledger != null) {
            ledger.close();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PayoutRepository payoutRepository;

    @Autowired
    private PayoutSagaRepository sagaRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private DailyPayoutJob job;

    @Autowired
    private PayoutBatchService batchService;

    @Autowired
    private MeterRegistry meterRegistry;

    private StubLedger ledger() {
        if (ledger == null) {
            ledger = new StubLedger(kafka.getBootstrapServers());
        }
        return ledger;
    }

    @AfterEach
    void reset() {
        stub.reset();
        ledger().alwaysSucceed();
    }

    // ---------------------------------------------------------------- helpers

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, UUID merchantId, String... scopes) {
        List<org.springframework.security.core.GrantedAuthority> authorities = new ArrayList<>();
        for (String scope : scopes) {
            authorities.add(new SimpleGrantedAuthority("SCOPE_" + scope));
        }
        return request.with(jwt().jwt(j -> {
            if (merchantId != null) {
                j.claim("merchant_id", merchantId.toString());
            }
        }).authorities(authorities));
    }

    private record Reply(int status, JsonNode body, String replayed) {
    }

    private Reply call(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mockMvc.perform(request).andReturn();
        String content = result.getResponse().getContentAsString();
        return new Reply(result.getResponse().getStatus(), content.isBlank() ? null : objectMapper.readTree(content),
                result.getResponse().getHeader("Idempotent-Replayed"));
    }

    /** A merchant with a destination and a payable balance. */
    private UUID merchant(String bankAccount, String payable) throws Exception {
        ledger();
        UUID merchantId = UUID.randomUUID();
        Reply reply = call(as(put("/api/v1/payouts/destination"), merchantId, "payouts:write")
                .contentType(MediaType.APPLICATION_JSON).content("{\"bankAccount\":\"" + bankAccount + "\"}"));
        assertThat(reply.status()).isEqualTo(200);
        stub.payable.put(merchantId, new BigDecimal(payable));
        return merchantId;
    }

    private Reply instant(UUID merchantId, String key, String amount) throws Exception {
        return call(as(post("/api/v1/payouts"), merchantId, "payouts:write").header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":" + amount + ",\"currency\":\"USD\"}"));
    }

    private UUID payout(UUID merchantId, String amount) throws Exception {
        Reply reply = instant(merchantId, UUID.randomUUID().toString(), amount);
        assertThat(reply.status()).isEqualTo(202);
        assertThat(reply.body().get("status").asText()).isEqualTo("PENDING");
        return UUID.fromString(reply.body().get("payoutId").asText().substring(3));
    }

    private Payout awaitPayout(UUID payoutId, PayoutStatus status) {
        await().atMost(Duration.ofSeconds(15)).until(() -> payoutRepository.findById(payoutId).orElseThrow().getStatus() == status);
        return payoutRepository.findById(payoutId).orElseThrow();
    }

    private PayoutSaga saga(UUID payoutId) {
        return sagaRepository.findByPayoutId(payoutId).orElseThrow();
    }

    private void awaitSaga(UUID payoutId, SagaState state) {
        await().atMost(Duration.ofSeconds(15)).until(() -> saga(payoutId).getState() == state);
    }

    private List<String> events(UUID payoutId) {
        return outboxEventRepository.findAll().stream()
                .filter(e -> e.getAggregateId().equals(payoutId) && e.getTopic().equals("payout-events"))
                .sorted(java.util.Comparator.comparing(OutboxEvent::getSeq))
                .map(OutboxEvent::getEventType).toList();
    }

    // ---------------------------------------------------------------- payouts

    @Test
    void anInstantPayoutIsHeldTransferredPaidAndWatchedUntilTheReturnWindowCloses() throws Exception {
        UUID merchantId = merchant("ba_test_ok", "100.00");

        UUID payoutId = payout(merchantId, "60.00");

        Payout paid = awaitPayout(payoutId, PayoutStatus.PAID);
        assertThat(paid.getTransferId()).startsWith("tr_");
        assertThat(paid.getTrigger()).isEqualTo(PayoutTrigger.INSTANT);
        assertThat(saga(payoutId).getState()).isEqualTo(SagaState.RETURN_WINDOW);
        awaitSaga(payoutId, SagaState.COMPLETED);
        assertThat(payoutRepository.findById(payoutId).orElseThrow().getStatus()).isEqualTo(PayoutStatus.PAID);

        assertThat(ledger().commandTypesFor(payoutId)).containsExactly("HOLD_PAYOUT", "FINALIZE_PAYOUT");
        assertThat(ledger().commandsFor(payoutId).getFirst().cutoff()).isNotNull();
        List<StubServer.Request> transfers = stub.transferRequests("po_" + payoutId);
        assertThat(transfers).hasSize(1);
        assertThat(transfers.getFirst().idempotencyKey()).isEqualTo(saga(payoutId).getId() + ":transfer");
        await().atMost(Duration.ofSeconds(5)).until(() -> events(payoutId).size() == 2);
        assertThat(events(payoutId)).containsExactly("PAYOUT_CREATED", "PAYOUT_PAID");
    }

    @Test
    void aRetryWithTheSameKeyReturnsTheSamePayoutAndADifferentRequestIsRefused() throws Exception {
        UUID merchantId = merchant("ba_test_ok", "100.00");
        String key = UUID.randomUUID().toString();

        Reply first = instant(merchantId, key, "10.00");
        Reply retry = instant(merchantId, key, "10.0");
        Reply different = instant(merchantId, key, "11.00");

        assertThat(first.status()).isEqualTo(202);
        assertThat(retry.status()).isEqualTo(200);
        assertThat(retry.replayed()).isEqualTo("true");
        assertThat(retry.body().get("payoutId").asText()).isEqualTo(first.body().get("payoutId").asText());
        assertThat(different.status()).isEqualTo(422);
        assertThat(different.body().get("message").asText()).contains("idempotency_key_reused");
    }

    @Test
    void aPayoutNeedsADestinationAndAPayableBalance() throws Exception {
        UUID noDestination = UUID.randomUUID();
        stub.payable.put(noDestination, new BigDecimal("50.00"));
        UUID merchantId = merchant("ba_test_ok", "50.00");

        Reply withoutDestination = instant(noDestination, UUID.randomUUID().toString(), "10.00");
        Reply tooMuch = instant(merchantId, UUID.randomUUID().toString(), "50.01");

        assertThat(withoutDestination.status()).isEqualTo(422);
        assertThat(withoutDestination.body().get("message").asText()).contains("no_payout_destination");
        assertThat(tooMuch.status()).isEqualTo(422);
        assertThat(tooMuch.body().get("message").asText()).contains("insufficient_payable_balance");
        assertThat(payoutRepository.findAll()).noneMatch(p -> p.getMerchantId().equals(merchantId));
    }

    @Test
    void aDeclinedTransferReleasesTheHoldAndFailsThePayout() throws Exception {
        UUID payoutId = payout(merchant("ba_test_invalid", "40.00"), "40.00");

        Payout failed = awaitPayout(payoutId, PayoutStatus.FAILED);

        assertThat(failed.getFailureCode()).isEqualTo("invalid_account");
        assertThat(ledger().commandTypesFor(payoutId)).containsExactly("HOLD_PAYOUT", "RELEASE_PAYOUT");
        assertThat(saga(payoutId).getState()).isEqualTo(SagaState.FAILED);
        await().atMost(Duration.ofSeconds(5)).until(() -> events(payoutId).contains("PAYOUT_FAILED"));
    }

    @Test
    void aTransferTheBankFailsLaterIsReleasedToo() throws Exception {
        UUID payoutId = payout(merchant("ba_test_closed", "40.00"), "25.00");

        Payout failed = awaitPayout(payoutId, PayoutStatus.FAILED);

        assertThat(failed.getFailureCode()).isEqualTo("account_closed");
        assertThat(failed.getTransferId()).isNotNull();
        assertThat(ledger().commandTypesFor(payoutId)).containsExactly("HOLD_PAYOUT", "RELEASE_PAYOUT");
    }

    @Test
    void aPaidTransferThatComesBackIsBookedAsReturned() throws Exception {
        UUID payoutId = payout(merchant("ba_test_returned", "80.00"), "80.00");

        awaitPayout(payoutId, PayoutStatus.PAID);
        Payout returned = awaitPayout(payoutId, PayoutStatus.RETURNED);

        assertThat(returned.getFailureCode()).isEqualTo("account_frozen");
        assertThat(returned.getPaidAt()).isNotNull();
        assertThat(ledger().commandTypesFor(payoutId)).containsExactly("HOLD_PAYOUT", "FINALIZE_PAYOUT", "RETURN_PAYOUT");
        await().atMost(Duration.ofSeconds(5)).until(() -> events(payoutId).size() == 3);
        assertThat(events(payoutId)).containsExactly("PAYOUT_CREATED", "PAYOUT_PAID", "PAYOUT_RETURNED");
    }

    @Test
    void aHoldTheLedgerRefusesFailsThePayoutWithoutTouchingTheBank() throws Exception {
        UUID merchantId = merchant("ba_test_ok", "30.00");
        ledger().decideWith(command -> command.commandType().equals("HOLD_PAYOUT")
                ? new String[]{"REJECTED", "INSUFFICIENT_FUNDS"} : new String[]{"SUCCEEDED", null});

        UUID payoutId = payout(merchantId, "30.00");

        assertThat(awaitPayout(payoutId, PayoutStatus.FAILED).getFailureCode()).isEqualTo("insufficient_payable_balance");
        assertThat(stub.transferRequests("po_" + payoutId)).isEmpty();
    }

    @Test
    void aLostTransferAnswerIsRetriedUnderTheSameKeyAndPaysOnce() throws Exception {
        UUID payoutId = payout(merchant("ba_test_lost_answer", "20.00"), "20.00");

        awaitPayout(payoutId, PayoutStatus.PAID);

        List<StubServer.Request> attempts = stub.transferRequests("po_" + payoutId);
        assertThat(attempts.size()).isGreaterThanOrEqualTo(2);
        assertThat(attempts).extracting(StubServer.Request::idempotencyKey).containsOnly(saga(payoutId).getId() + ":transfer");
        assertThat(stub.transfersFor("po_" + payoutId)).isEqualTo(1);
    }

    @Test
    void aBankOutageDuringSubmissionParksTheSagaWithTheHoldKeptUntilAnOperatorRetries() throws Exception {
        UUID merchantId = merchant("ba_test_ok", "20.00");
        stub.bankDown = true;
        UUID payoutId = payout(merchantId, "20.00");

        awaitSaga(payoutId, SagaState.REQUIRES_ATTENTION);
        assertThat(saga(payoutId).getStuckState()).isEqualTo(SagaState.SUBMITTING);
        assertThat(ledger().commandTypesFor(payoutId)).containsExactly("HOLD_PAYOUT");
        assertThat(meterRegistry.get("sagas.requires_attention").gauge().value()).isGreaterThanOrEqualTo(1.0);

        stub.bankDown = false;
        Reply retry = call(as(post("/api/v1/payouts/po_" + payoutId + "/saga/retry"), null, "payouts:operate"));

        assertThat(retry.status()).isEqualTo(200);
        awaitPayout(payoutId, PayoutStatus.PAID);
        assertThat(stub.transfersFor("po_" + payoutId)).isEqualTo(1);
    }

    @Test
    void aTransferStuckInTransitParksTheSagaAndResumesWhenTheBankPaysIt() throws Exception {
        UUID payoutId = payout(merchant("ba_test_slow", "20.00"), "20.00");

        awaitSaga(payoutId, SagaState.REQUIRES_ATTENTION);
        assertThat(saga(payoutId).getStuckState()).isEqualTo(SagaState.IN_TRANSIT);
        assertThat(payoutRepository.findById(payoutId).orElseThrow().getStatus()).isEqualTo(PayoutStatus.IN_TRANSIT);

        stub.slowReleased = true;
        call(as(post("/api/v1/payouts/po_" + payoutId + "/saga/retry"), null, "payouts:operate"));

        awaitPayout(payoutId, PayoutStatus.PAID);
    }

    // ---------------------------------------------------------------- batch

    @Test
    void theBatchPaysEachMerchantWithADestinationOnceADayEvenWithTwoReplicas() throws Exception {
        UUID withDestination = merchant("ba_test_ok", "35.00");
        UUID withoutDestination = UUID.randomUUID();
        stub.payable.put(withoutDestination, new BigDecimal("40.00"));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Optional<PayoutBatch>> runs = new ArrayList<>();
        try {
            List<Callable<Optional<PayoutBatch>>> replicas = List.of(job::runNow, job::runNow);
            for (Future<Optional<PayoutBatch>> future : pool.invokeAll(replicas)) {
                runs.add(future.get());
            }
        } finally {
            pool.shutdownNow();
        }
        // and once more without the lock: the batch itself only creates what is missing
        batchService.run(LocalDate.now(ZoneOffset.UTC));

        assertThat(runs.stream().filter(Optional::isPresent).count()).isEqualTo(1);
        PayoutBatch batch = runs.stream().flatMap(Optional::stream).findFirst().orElseThrow();
        assertThat(batch.getStatus()).isEqualTo(PayoutBatch.Status.COMPLETED);
        assertThat(batch.getSkippedNoDestination()).isGreaterThanOrEqualTo(1);
        List<Payout> payouts = payoutRepository.findAll().stream().filter(p -> p.getMerchantId().equals(withDestination)).toList();
        assertThat(payouts).hasSize(1);
        assertThat(payouts.getFirst().getAmount()).isEqualByComparingTo("35.00");
        assertThat(payouts.getFirst().getTrigger()).isEqualTo(PayoutTrigger.BATCH);
        assertThat(payoutRepository.findAll()).noneMatch(p -> p.getMerchantId().equals(withoutDestination));
        awaitPayout(payouts.getFirst().getId(), PayoutStatus.PAID);
        assertThat(meterRegistry.get("payout.batch.last.success.timestamp").gauge().value()).isGreaterThan(0);
    }

    @Test
    void outcomeCountersExistFromStartupSoAlertsSeeTheFirstFailure() {
        // a counter series that first appears at 1 makes Prometheus' increase() miss that event
        for (String status : List.of("IN_TRANSIT", "PAID", "FAILED", "RETURNED")) {
            for (String trigger : List.of("BATCH", "INSTANT")) {
                assertThat(meterRegistry.find("payouts.status.transitions").tag("status", status).tag("trigger", trigger).counter())
                        .as(status + "/" + trigger).isNotNull();
            }
        }
    }

    // ---------------------------------------------------------------- API

    @Test
    void merchantsSeeOnlyTheirOwnPayoutsAndOperatorsSeeAll() throws Exception {
        UUID owner = merchant("ba_test_ok", "10.00");
        UUID payoutId = payout(owner, "10.00");
        UUID other = UUID.randomUUID();

        Reply own = call(as(get("/api/v1/payouts/po_" + payoutId), owner, "payouts:read"));
        Reply someoneElses = call(as(get("/api/v1/payouts/po_" + payoutId), other, "payouts:read"));
        Reply malformed = call(as(get("/api/v1/payouts/" + payoutId), owner, "payouts:read"));
        Reply operator = call(as(get("/api/v1/payouts/po_" + payoutId), null, "payouts:operate"));
        Reply ownList = call(as(get("/api/v1/payouts"), owner, "payouts:read"));
        Reply withoutScope = call(as(get("/api/v1/payouts/po_" + payoutId), owner, "payments:read"));

        assertThat(own.status()).isEqualTo(200);
        assertThat(own.body().get("bankAccount").asText()).isEqualTo("ba_test_ok");
        assertThat(someoneElses.status()).isEqualTo(404);
        assertThat(malformed.status()).isEqualTo(400);
        assertThat(operator.status()).isEqualTo(200);
        assertThat(ownList.body()).hasSize(1);
        assertThat(withoutScope.status()).isEqualTo(403);
    }

    @Test
    void theLookupIsForAuditorsAndShowsWhetherMoneyIsStillMoving() throws Exception {
        UUID merchantId = merchant("ba_test_slow", "10.00");
        UUID payoutId = payout(merchantId, "10.00");
        String body = "{\"payoutIds\":[\"po_" + payoutId + "\",\"po_" + UUID.randomUUID() + "\"]}";

        Reply asMerchant = call(as(post("/api/v1/payouts/lookup"), merchantId, "payouts:read", "payouts:write")
                .contentType(MediaType.APPLICATION_JSON).content(body));
        Reply asAuditor = call(as(post("/api/v1/payouts/lookup"), null, "payouts:audit")
                .contentType(MediaType.APPLICATION_JSON).content(body));

        assertThat(asMerchant.status()).isEqualTo(403);
        assertThat(asAuditor.status()).isEqualTo(200);
        assertThat(asAuditor.body()).hasSize(1);
        assertThat(asAuditor.body().get(0).get("inFlight").asBoolean()).isTrue();
        assertThat(asAuditor.body().get(0).get("merchantId").asText()).isEqualTo(merchantId.toString());
    }

    @Test
    void destinationsMustBeBankAccountTokens() throws Exception {
        UUID merchantId = UUID.randomUUID();

        Reply invalid = call(as(put("/api/v1/payouts/destination"), merchantId, "payouts:write")
                .contentType(MediaType.APPLICATION_JSON).content("{\"bankAccount\":\"DE89 3704 0044\"}"));
        Reply none = call(as(get("/api/v1/payouts/destination"), merchantId, "payouts:read"));
        Reply operatorHasNoMerchant = call(as(put("/api/v1/payouts/destination"), null, "payouts:write")
                .contentType(MediaType.APPLICATION_JSON).content("{\"bankAccount\":\"ba_test_ok\"}"));

        assertThat(invalid.status()).isEqualTo(400);
        assertThat(none.status()).isEqualTo(404);
        assertThat(operatorHasNoMerchant.status()).isEqualTo(403);
    }
}
