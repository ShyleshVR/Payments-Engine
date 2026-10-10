package com.shylesh.processor_simulator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shylesh.processor_simulator.persistence.TransferRepository;

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

import java.time.Duration;
import java.time.LocalDateTime;
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
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Bank transfers (payouts) against a real Postgres, with the bank's clock sped up to milliseconds. */
@SpringBootTest(properties = {
        "management.tracing.enabled=false",
        "processor.api-key=test-key",
        "processor.bank.settle-delay=300ms",
        "processor.bank.return-delay=600ms",
        "processor.bank.slow-settle-delay=1h",
        "processor.bank.clock-interval=100ms"
})
@AutoConfigureMockMvc
@Testcontainers
class BankTransfersIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TransferRepository transferRepository;

    @AfterEach
    void endOutage() throws Exception {
        mockMvc.perform(post("/v1/test/outage").param("seconds", "0").header("X-Api-Key", "test-key"));
    }

    private record Reply(int status, JsonNode body, boolean replayed) {
    }

    private Reply transfer(String key, String bankAccount, String amount, String reference) throws Exception {
        MvcResult result = mockMvc.perform(post("/v1/transfers").header("X-Api-Key", "test-key").header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bankAccount\":\"" + bankAccount + "\",\"amount\":" + amount
                                + ",\"currency\":\"USD\",\"reference\":\"" + reference + "\"}"))
                .andReturn();
        return new Reply(result.getResponse().getStatus(), objectMapper.readTree(result.getResponse().getContentAsString()),
                "true".equals(result.getResponse().getHeader("Idempotent-Replayed")));
    }

    private Reply status(String transferId) throws Exception {
        MvcResult result = mockMvc.perform(get("/v1/transfers/" + transferId).header("X-Api-Key", "test-key")).andReturn();
        return new Reply(result.getResponse().getStatus(), objectMapper.readTree(result.getResponse().getContentAsString()), false);
    }

    private String statusOf(String transferId) throws Exception {
        return status(transferId).body().get("status").asText();
    }

    private String accepted(String bankAccount) throws Exception {
        Reply reply = transfer(key(), bankAccount, "25.00", "po_" + UUID.randomUUID());
        assertThat(reply.status()).isEqualTo(201);
        assertThat(reply.body().get("status").asText()).isEqualTo("PENDING");
        return reply.body().get("id").asText();
    }

    private static String key() {
        return "test-" + UUID.randomUUID();
    }

    @Test
    void aTransferIsAcceptedThenPaidByTheBank() throws Exception {
        String id = accepted("ba_test_ok");

        await().atMost(Duration.ofSeconds(5)).until(() -> statusOf(id).equals("PAID"));
        JsonNode paid = status(id).body();
        assertThat(paid.get("paidAt").isNull()).isFalse();
        assertThat(paid.get("amount").decimalValue()).isEqualByComparingTo("25.00");
        // paid is final for an ordinary account
        Thread.sleep(800);
        assertThat(statusOf(id)).isEqualTo("PAID");
    }

    @Test
    void aRetryWithTheSameKeyReplaysTheAnswerAndTransfersOnce() throws Exception {
        String key = key();
        String reference = "po_" + UUID.randomUUID();
        long before = transferRepository.count();

        Reply first = transfer(key, "ba_test_ok", "40.00", reference);
        Reply retry = transfer(key, "ba_test_ok", "40.00", reference);
        Reply changed = transfer(key, "ba_test_ok", "41.00", reference);

        assertThat(retry.replayed()).isTrue();
        assertThat(retry.body().get("id").asText()).isEqualTo(first.body().get("id").asText());
        assertThat(changed.status()).isEqualTo(422);
        assertThat(transferRepository.count()).isEqualTo(before + 1);
    }

    @Test
    void concurrentDuplicatesProduceOneTransfer() throws Exception {
        String key = key();
        String reference = "po_" + UUID.randomUUID();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Reply>> calls = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                calls.add(() -> transfer(key, "ba_test_ok", "10.00", reference));
            }
            Set<String> ids = new HashSet<>();
            for (Future<Reply> future : pool.invokeAll(calls)) {
                assertThat(future.get().status()).isEqualTo(201);
                ids.add(future.get().body().get("id").asText());
            }
            assertThat(ids).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void invalidAndUnknownAccountsAreRejectedAtOnce() throws Exception {
        long before = transferRepository.count();

        Reply invalid = transfer(key(), "ba_test_invalid", "10.00", "po_x");
        Reply unknown = transfer(key(), "ba_somebody_else", "10.00", "po_y");

        assertThat(invalid.status()).isEqualTo(402);
        assertThat(invalid.body().get("code").asText()).isEqualTo("invalid_account");
        assertThat(unknown.status()).isEqualTo(402);
        assertThat(transferRepository.count()).isEqualTo(before);
    }

    @Test
    void aClosedAccountFailsTheTransferAfterAcceptingIt() throws Exception {
        String id = accepted("ba_test_closed");

        await().atMost(Duration.ofSeconds(5)).until(() -> statusOf(id).equals("FAILED"));
        JsonNode failed = status(id).body();
        assertThat(failed.get("failureCode").asText()).isEqualTo("account_closed");
        assertThat(failed.get("paidAt").isNull()).isTrue();
    }

    @Test
    void aPaidTransferCanComeBackLater() throws Exception {
        String id = accepted("ba_test_returned");

        await().atMost(Duration.ofSeconds(5)).until(() -> statusOf(id).equals("PAID"));
        await().atMost(Duration.ofSeconds(5)).until(() -> statusOf(id).equals("RETURNED"));
        JsonNode returned = status(id).body();
        assertThat(returned.get("failureCode").asText()).isEqualTo("account_frozen");
        assertThat(returned.get("paidAt").isNull()).isFalse();
        assertThat(returned.get("returnedAt").isNull()).isFalse();
    }

    @Test
    void aSlowTransferStaysInTransit() throws Exception {
        String id = accepted("ba_test_slow");

        Thread.sleep(800);

        assertThat(statusOf(id)).isEqualTo("PENDING");
    }

    @Test
    void anOutageAnswers503AndCreatesNothing() throws Exception {
        String id = accepted("ba_test_slow");
        mockMvc.perform(post("/v1/test/outage").param("seconds", "60").header("X-Api-Key", "test-key"));
        long before = transferRepository.count();

        Reply duringOutage = transfer(key(), "ba_test_ok", "10.00", "po_z");
        Reply statusDuringOutage = status(id);

        assertThat(duringOutage.status()).isEqualTo(503);
        assertThat(statusDuringOutage.status()).isEqualTo(503);
        assertThat(transferRepository.count()).isEqualTo(before);
    }

    @Test
    void unknownTransfersAre404() throws Exception {
        assertThat(status("tr_" + UUID.randomUUID().toString().replace("-", "")).status()).isEqualTo(404);
    }

    @Test
    void theSettlementReportListsTransfers() throws Exception {
        String reference = "po_" + UUID.randomUUID();
        Reply reply = transfer(key(), "ba_test_returned", "33.00", reference);
        String id = reply.body().get("id").asText();
        await().atMost(Duration.ofSeconds(5)).until(() -> statusOf(id).equals("RETURNED"));

        JsonNode report = objectMapper.readTree(mockMvc.perform(get("/v1/reports/settlement").header("X-Api-Key", "test-key")
                        .param("from", LocalDateTime.now().minusHours(1).toString())
                        .param("to", LocalDateTime.now().plusHours(1).toString()))
                .andReturn().getResponse().getContentAsString());

        JsonNode line = null;
        for (JsonNode t : report.get("transfers")) {
            if (t.get("id").asText().equals(id)) {
                line = t;
            }
        }
        assertThat(line).isNotNull();
        assertThat(line.get("reference").asText()).isEqualTo(reference);
        assertThat(line.get("status").asText()).isEqualTo("RETURNED");
        assertThat(line.get("amount").decimalValue()).isEqualByComparingTo("33.00");
        assertThat(line.get("paidAt").isNull()).isFalse();
        assertThat(line.get("returnedAt").isNull()).isFalse();
    }
}
