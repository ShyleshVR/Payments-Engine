package com.shylesh.processor_simulator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shylesh.processor_simulator.persistence.AuthorizationRepository;
import com.shylesh.processor_simulator.persistence.AuthorizationStatus;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** The processor API against a real Postgres: idempotency, state rules, test payment methods, faults. */
@SpringBootTest(properties = {
        "management.tracing.enabled=false",
        "processor.api-key=test-key",
        "processor.timeout-once-delay=600ms",
        "processor.flaky-failures=2"
})
@AutoConfigureMockMvc
@Testcontainers
class ProcessorApiIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuthorizationRepository authorizationRepository;

    @AfterEach
    void endOutage() throws Exception {
        mockMvc.perform(post("/v1/test/outage").param("seconds", "0").header("X-Api-Key", "test-key"));
    }

    private record Reply(int status, JsonNode body, boolean replayed) {
    }

    private Reply call(String path, String key, String json) throws Exception {
        var request = post(path).header("X-Api-Key", "test-key").contentType(MediaType.APPLICATION_JSON);
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        if (json != null) {
            request.content(json);
        }
        MvcResult result = mockMvc.perform(request).andReturn();
        String content = result.getResponse().getContentAsString();
        return new Reply(result.getResponse().getStatus(), content.isEmpty() ? null : objectMapper.readTree(content),
                "true".equals(result.getResponse().getHeader("Idempotent-Replayed")));
    }

    private Reply authorize(String key, String paymentMethod, String amount) throws Exception {
        return call("/v1/authorizations", key,
                "{\"paymentMethod\":\"" + paymentMethod + "\",\"amount\":" + amount + ",\"currency\":\"USD\",\"reference\":\"pay_test\"}");
    }

    private String authorized(String paymentMethod) throws Exception {
        Reply reply = authorize(key(), paymentMethod, "100.00");
        assertThat(reply.status()).isEqualTo(201);
        return reply.body().get("id").asText();
    }

    private String captured(String paymentMethod) throws Exception {
        String id = authorized(paymentMethod);
        assertThat(call("/v1/authorizations/" + id + "/capture", key(), null).status()).isEqualTo(200);
        return id;
    }

    private static String key() {
        return "test-" + UUID.randomUUID();
    }

    @Test
    void retryWithTheSameKeyReplaysTheFirstAnswerAndAuthorizesOnce() throws Exception {
        String key = key();
        long before = authorizationRepository.count();

        Reply first = authorize(key, "pm_card_visa", "25.50");
        Reply retry = authorize(key, "pm_card_visa", "25.5");

        assertThat(first.status()).isEqualTo(201);
        assertThat(first.body().get("status").asText()).isEqualTo("AUTHORIZED");
        assertThat(retry.status()).isEqualTo(201);
        assertThat(retry.replayed()).isTrue();
        assertThat(retry.body()).isEqualTo(first.body());
        assertThat(authorizationRepository.count()).isEqualTo(before + 1);
    }

    @Test
    void sameKeyForADifferentRequestIsRejected() throws Exception {
        String key = key();
        authorize(key, "pm_card_visa", "10.00");

        Reply reused = authorize(key, "pm_card_visa", "11.00");

        assertThat(reused.status()).isEqualTo(422);
        assertThat(reused.body().get("code").asText()).isEqualTo("idempotency_key_reused");
    }

    @Test
    void concurrentDuplicatesProduceOneAuthorizationAndOneAnswer() throws Exception {
        String key = key();
        long before = authorizationRepository.count();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Reply>> calls = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                calls.add(() -> authorize(key, "pm_card_visa", "42.00"));
            }
            Set<String> ids = new HashSet<>();
            for (Future<Reply> future : pool.invokeAll(calls)) {
                Reply reply = future.get();
                assertThat(reply.status()).isEqualTo(201);
                ids.add(reply.body().get("id").asText());
            }
            assertThat(ids).hasSize(1);
            assertThat(authorizationRepository.count()).isEqualTo(before + 1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void declinesAre402WithTheIssuerCode() throws Exception {
        Reply declined = authorize(key(), "pm_card_declined", "10.00");
        Reply insufficient = authorize(key(), "pm_card_insufficient_funds", "10.00");
        Reply unknown = authorize(key(), "pm_card_unknown", "10.00");

        assertThat(declined.status()).isEqualTo(402);
        assertThat(declined.body().get("code").asText()).isEqualTo("do_not_honor");
        assertThat(insufficient.body().get("code").asText()).isEqualTo("insufficient_funds");
        assertThat(unknown.status()).isEqualTo(422);
    }

    @Test
    void captureOnceThenNeitherCaptureNorVoidAgain() throws Exception {
        String id = authorized("pm_card_visa");

        Reply capture = call("/v1/authorizations/" + id + "/capture", key(), null);
        Reply captureAgain = call("/v1/authorizations/" + id + "/capture", key(), null);
        Reply voidAfterCapture = call("/v1/authorizations/" + id + "/void", key(), null);

        assertThat(capture.status()).isEqualTo(200);
        assertThat(capture.body().get("status").asText()).isEqualTo("CAPTURED");
        assertThat(captureAgain.body().get("code").asText()).isEqualTo("already_captured");
        assertThat(voidAfterCapture.status()).isEqualTo(409);
    }

    @Test
    void declinedCaptureLeavesTheAuthorizationOpenToVoid() throws Exception {
        String id = authorized("pm_card_capture_fails");

        Reply capture = call("/v1/authorizations/" + id + "/capture", key(), null);
        Reply voided = call("/v1/authorizations/" + id + "/void", key(), null);
        Reply voidedAgain = call("/v1/authorizations/" + id + "/void", key(), null);

        assertThat(capture.status()).isEqualTo(402);
        assertThat(capture.body().get("code").asText()).isEqualTo("capture_declined");
        assertThat(voided.body().get("status").asText()).isEqualTo("VOIDED");
        assertThat(voidedAgain.status()).isEqualTo(200);
    }

    @Test
    void refundsAreLimitedToTheCapturedAmount() throws Exception {
        String id = captured("pm_card_visa");

        Reply tooMuch = call("/v1/refunds", key(), "{\"authorizationId\":\"" + id + "\",\"amount\":100.01}");
        Reply partial = call("/v1/refunds", key(), "{\"authorizationId\":\"" + id + "\",\"amount\":60.00}");
        Reply beyondRest = call("/v1/refunds", key(), "{\"authorizationId\":\"" + id + "\",\"amount\":40.01}");
        Reply rest = call("/v1/refunds", key(), "{\"authorizationId\":\"" + id + "\",\"amount\":40.00}");

        assertThat(tooMuch.body().get("code").asText()).isEqualTo("amount_exceeds_refundable");
        assertThat(partial.status()).isEqualTo(201);
        assertThat(beyondRest.status()).isEqualTo(422);
        assertThat(rest.status()).isEqualTo(201);
        assertThat(authorizationRepository.findById(id).orElseThrow().getRefundedAmount()).isEqualByComparingTo("100.00");
    }

    @Test
    void refundOfAnUncapturedOrRefundFailingPaymentIsRefused() throws Exception {
        String open = authorized("pm_card_visa");
        String failing = captured("pm_card_refund_fails");

        Reply notCaptured = call("/v1/refunds", key(), "{\"authorizationId\":\"" + open + "\",\"amount\":1.00}");
        Reply declined = call("/v1/refunds", key(), "{\"authorizationId\":\"" + failing + "\",\"amount\":1.00}");

        assertThat(notCaptured.body().get("code").asText()).isEqualTo("not_captured");
        assertThat(declined.status()).isEqualTo(402);
        assertThat(declined.body().get("code").asText()).isEqualTo("refund_declined");
    }

    @Test
    void reversalBeforeTheAuthorizationArrivesBlocksIt() throws Exception {
        String originalKey = key();

        Reply reversal = call("/v1/reversals", key(), "{\"originalIdempotencyKey\":\"" + originalKey + "\"}");
        Reply lateAuthorization = authorize(originalKey, "pm_card_visa", "10.00");

        assertThat(reversal.status()).isEqualTo(200);
        assertThat(reversal.body().get("status").asText()).isEqualTo("REVERSED");
        assertThat(reversal.body().get("authorizationId").isNull()).isTrue();
        assertThat(lateAuthorization.status()).isEqualTo(409);
        assertThat(lateAuthorization.body().get("code").asText()).isEqualTo("reversed");
        JsonNode inquiry = objectMapper.readTree(mockMvc.perform(get("/v1/operations/" + originalKey).header("X-Api-Key", "test-key"))
                .andReturn().getResponse().getContentAsString());
        assertThat(inquiry.get("state").asText()).isEqualTo("REVERSED_BEFORE_PROCESSING");
    }

    @Test
    void reversalOfAnApprovedAuthorizationVoidsIt() throws Exception {
        String originalKey = key();
        String id = authorize(originalKey, "pm_card_visa", "10.00").body().get("id").asText();

        Reply reversal = call("/v1/reversals", key(), "{\"originalIdempotencyKey\":\"" + originalKey + "\"}");

        assertThat(reversal.body().get("authorizationId").asText()).isEqualTo(id);
        assertThat(authorizationRepository.findById(id).orElseThrow().getStatus()).isEqualTo(AuthorizationStatus.VOIDED);
    }

    @Test
    void inquiryReportsTheStoredOutcomeOr404() throws Exception {
        String key = key();
        authorize(key, "pm_card_declined", "10.00");

        JsonNode known = objectMapper.readTree(mockMvc.perform(get("/v1/operations/" + key).header("X-Api-Key", "test-key"))
                .andReturn().getResponse().getContentAsString());
        int unknown = mockMvc.perform(get("/v1/operations/" + key()).header("X-Api-Key", "test-key")).andReturn().getResponse().getStatus();

        assertThat(known.get("responseStatus").asInt()).isEqualTo(402);
        assertThat(known.get("response").get("code").asText()).isEqualTo("do_not_honor");
        assertThat(unknown).isEqualTo(404);
    }

    @Test
    void flakyMethodFailsTwiceThenSucceedsWithTheSameKey() throws Exception {
        String key = key();

        int first = authorize(key, "pm_card_flaky", "10.00").status();
        int second = authorize(key, "pm_card_flaky", "10.00").status();
        Reply third = authorize(key, "pm_card_flaky", "10.00");

        assertThat(first).isEqualTo(503);
        assertThat(second).isEqualTo(503);
        assertThat(third.status()).isEqualTo(201);
    }

    @Test
    void timeoutOnceAnswersLateTheFirstTimeAndAtOnceOnRetry() throws Exception {
        String key = key();

        long start = System.nanoTime();
        Reply first = authorize(key, "pm_card_timeout_once", "10.00");
        long firstMillis = (System.nanoTime() - start) / 1_000_000;
        start = System.nanoTime();
        Reply retry = authorize(key, "pm_card_timeout_once", "10.00");
        long retryMillis = (System.nanoTime() - start) / 1_000_000;

        assertThat(first.status()).isEqualTo(201);
        assertThat(firstMillis).isGreaterThanOrEqualTo(600);
        assertThat(retry.replayed()).isTrue();
        assertThat(retryMillis).isLessThan(600);
    }

    @Test
    void outageAnswers503WithoutProcessing() throws Exception {
        call("/v1/test/outage?seconds=60", null, null);
        String key = key();

        Reply during = authorize(key, "pm_card_visa", "10.00");
        call("/v1/test/outage?seconds=0", null, null);
        Reply after = authorize(key, "pm_card_visa", "10.00");

        assertThat(during.status()).isEqualTo(503);
        assertThat(after.status()).isEqualTo(201);
        assertThat(after.replayed()).isFalse();
    }

    @Test
    void apiKeyAndIdempotencyKeyAreRequired() throws Exception {
        int noApiKey = mockMvc.perform(post("/v1/authorizations").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn().getResponse().getStatus();
        int wrongApiKey = mockMvc.perform(post("/v1/authorizations").header("X-Api-Key", "nope"))
                .andReturn().getResponse().getStatus();
        Reply noIdempotencyKey = call("/v1/authorizations", null,
                "{\"paymentMethod\":\"pm_card_visa\",\"amount\":1,\"currency\":\"USD\"}");
        int health = mockMvc.perform(get("/actuator/health")).andReturn().getResponse().getStatus();

        assertThat(noApiKey).isEqualTo(401);
        assertThat(wrongApiKey).isEqualTo(401);
        assertThat(noIdempotencyKey.status()).isEqualTo(400);
        assertThat(health).isEqualTo(200);
    }
}
