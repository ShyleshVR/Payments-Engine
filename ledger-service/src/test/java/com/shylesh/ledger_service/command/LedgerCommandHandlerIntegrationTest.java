package com.shylesh.ledger_service.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shylesh.ledger_service.config.LedgerTopics;
import com.shylesh.ledger_service.dto.AccountBalanceResponse;
import com.shylesh.ledger_service.outbox.OutboxEvent;
import com.shylesh.ledger_service.outbox.OutboxEventRepository;
import com.shylesh.ledger_service.persistence.LedgerAccountType;
import com.shylesh.ledger_service.persistence.LedgerDirection;
import com.shylesh.ledger_service.persistence.LedgerEntry;
import com.shylesh.ledger_service.persistence.LedgerEntryRepository;
import com.shylesh.ledger_service.persistence.LedgerTransactionRepository;
import com.shylesh.ledger_service.service.LedgerQueryService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** Saga commands against a real Postgres: postings, replies in the outbox, idempotency, locking. */
@SpringBootTest(properties = {
        "management.tracing.enabled=false",
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.auto-create=false",
        "outbox.publisher.poll-interval=PT1H"
})
@Testcontainers
class LedgerCommandHandlerIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @Autowired
    private LedgerCommandHandler handler;

    @Autowired
    private LedgerQueryService queryService;

    @Autowired
    private LedgerTransactionRepository transactionRepository;

    @Autowired
    private LedgerEntryRepository entryRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private io.micrometer.core.instrument.MeterRegistry meterRegistry;

    private static LedgerCommand command(LedgerCommandType type, UUID sagaId, UUID paymentId, UUID merchantId, String amount) {
        return new LedgerCommand(UUID.randomUUID(), type.name(), sagaId, paymentId, merchantId, new BigDecimal(amount), "USD");
    }

    /** A merchant with a settled payment of the given amount. */
    private UUID merchantWithBalance(String amount) {
        UUID merchantId = UUID.randomUUID();
        handler.handle(command(LedgerCommandType.SETTLE_PAYMENT, UUID.randomUUID(), UUID.randomUUID(), merchantId, amount));
        return merchantId;
    }

    private AccountBalanceResponse balance(UUID merchantId) {
        return queryService.getBalance(LedgerAccountType.MERCHANT, merchantId, "USD");
    }

    private List<JsonNode> repliesFor(UUID paymentId) throws Exception {
        List<JsonNode> replies = new ArrayList<>();
        for (OutboxEvent event : outboxEventRepository.findAll()) {
            if (event.getAggregateId().equals(paymentId)) {
                assertThat(event.getTopic()).isEqualTo(LedgerTopics.REPLIES);
                replies.add(objectMapper.readTree(event.getPayload()));
            }
        }
        return replies;
    }

    @Test
    void settlementCreditsTheMerchantAndRepliesThroughTheOutbox() throws Exception {
        UUID merchantId = UUID.randomUUID();
        LedgerCommand settle = command(LedgerCommandType.SETTLE_PAYMENT, UUID.randomUUID(), UUID.randomUUID(), merchantId, "125.50");

        LedgerReply reply = handler.handle(settle);

        assertThat(reply.outcome()).isEqualTo(LedgerReply.SUCCEEDED);
        assertThat(balance(merchantId).getBalance()).isEqualByComparingTo("125.50");
        List<JsonNode> replies = repliesFor(settle.getPaymentId());
        assertThat(replies).hasSize(1);
        assertThat(replies.getFirst().get("eventType").asText()).isEqualTo("LEDGER_REPLY");
        assertThat(replies.getFirst().get("data").get("commandId").asText()).isEqualTo(settle.getCommandId().toString());
        assertThat(replies.getFirst().get("data").get("outcome").asText()).isEqualTo("SUCCEEDED");
    }

    @Test
    void unpublishedRepliesShowAsOutboxBacklog() {
        handler.handle(command(LedgerCommandType.SETTLE_PAYMENT, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "1.00"));

        // the relay polls hourly in this test, so the reply is still waiting
        assertThat(meterRegistry.get("outbox.pending").gauge().value()).isGreaterThanOrEqualTo(1);
        assertThat(meterRegistry.get("outbox.oldest.pending.age").gauge().value()).isGreaterThanOrEqualTo(0);
        assertThat(meterRegistry.get("outbox.failed").gauge().value()).isEqualTo(0);
    }

    @Test
    void repeatedCommandPostsOnceAndRepliesAgain() throws Exception {
        UUID merchantId = UUID.randomUUID();
        LedgerCommand settle = command(LedgerCommandType.SETTLE_PAYMENT, UUID.randomUUID(), UUID.randomUUID(), merchantId, "10.00");

        LedgerReply first = handler.handle(settle);
        LedgerReply again = handler.handle(settle);

        assertThat(again).isEqualTo(first);
        assertThat(transactionRepository.findByPaymentIdOrderByCreatedAtAsc(settle.getPaymentId())).hasSize(1);
        assertThat(repliesFor(settle.getPaymentId())).hasSize(2);
        assertThat(balance(merchantId).getBalance()).isEqualByComparingTo("10.00");
    }

    @Test
    void aPaymentIsNeverSettledTwiceEvenUnderADifferentCommandId() {
        UUID merchantId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();

        LedgerReply first = handler.handle(command(LedgerCommandType.SETTLE_PAYMENT, UUID.randomUUID(), paymentId, merchantId, "10.00"));
        LedgerReply second = handler.handle(command(LedgerCommandType.SETTLE_PAYMENT, UUID.randomUUID(), paymentId, merchantId, "10.00"));

        assertThat(second.outcome()).isEqualTo(LedgerReply.SUCCEEDED);
        assertThat(second.transactionId()).isEqualTo(first.transactionId());
        assertThat(balance(merchantId).getBalance()).isEqualByComparingTo("10.00");
    }

    @Test
    void holdIsRejectedWhenTheBalanceDoesNotCoverIt() {
        UUID merchantId = merchantWithBalance("50.00");

        LedgerReply reply = handler.handle(command(LedgerCommandType.HOLD_REFUND, UUID.randomUUID(), UUID.randomUUID(), merchantId, "50.01"));

        assertThat(reply.outcome()).isEqualTo(LedgerReply.REJECTED);
        assertThat(reply.reason()).isEqualTo(LedgerCommandHandler.INSUFFICIENT_FUNDS);
        assertThat(balance(merchantId).getBalance()).isEqualByComparingTo("50.00");
        assertThat(balance(merchantId).getReserved()).isEqualByComparingTo("0");
    }

    @Test
    void concurrentHoldsCannotSpendTheSameBalanceTwice() throws Exception {
        UUID merchantId = merchantWithBalance("100.00");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<LedgerReply>> holds = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                holds.add(() -> handler.handle(command(LedgerCommandType.HOLD_REFUND, UUID.randomUUID(), UUID.randomUUID(), merchantId, "30.00")));
            }
            int succeeded = 0;
            for (Future<LedgerReply> future : pool.invokeAll(holds)) {
                if (future.get().outcome().equals(LedgerReply.SUCCEEDED)) {
                    succeeded++;
                }
            }

            assertThat(succeeded).isEqualTo(3);
            assertThat(balance(merchantId).getBalance()).isEqualByComparingTo("10.00");
            assertThat(balance(merchantId).getReserved()).isEqualByComparingTo("90.00");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void releasedHoldReturnsTheAmountAndCannotBeReleasedTwice() {
        UUID merchantId = merchantWithBalance("80.00");
        UUID sagaId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        handler.handle(command(LedgerCommandType.HOLD_REFUND, sagaId, paymentId, merchantId, "30.00"));

        LedgerReply release = handler.handle(command(LedgerCommandType.RELEASE_HOLD, sagaId, paymentId, merchantId, "30.00"));
        LedgerReply releaseAgain = handler.handle(command(LedgerCommandType.RELEASE_HOLD, sagaId, paymentId, merchantId, "30.00"));

        assertThat(release.outcome()).isEqualTo(LedgerReply.SUCCEEDED);
        assertThat(releaseAgain.reason()).isEqualTo(LedgerCommandHandler.NO_ACTIVE_HOLD);
        assertThat(balance(merchantId).getBalance()).isEqualByComparingTo("80.00");
        assertThat(balance(merchantId).getReserved()).isEqualByComparingTo("0");
    }

    @Test
    void finalizedRefundLeavesThroughClearingAndEndsTheHold() {
        UUID merchantId = merchantWithBalance("80.00");
        UUID sagaId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        BigDecimal clearingBefore = queryService.getBalance(LedgerAccountType.PLATFORM_CLEARING, null, "USD").getBalance();
        handler.handle(command(LedgerCommandType.HOLD_REFUND, sagaId, paymentId, merchantId, "30.00"));

        LedgerReply finalized = handler.handle(command(LedgerCommandType.FINALIZE_REFUND, sagaId, paymentId, merchantId, "30.00"));
        LedgerReply releaseAfter = handler.handle(command(LedgerCommandType.RELEASE_HOLD, sagaId, paymentId, merchantId, "30.00"));

        assertThat(finalized.outcome()).isEqualTo(LedgerReply.SUCCEEDED);
        assertThat(releaseAfter.outcome()).isEqualTo(LedgerReply.REJECTED);
        assertThat(balance(merchantId).getBalance()).isEqualByComparingTo("50.00");
        assertThat(balance(merchantId).getReserved()).isEqualByComparingTo("0");
        assertThat(queryService.getBalance(LedgerAccountType.PLATFORM_CLEARING, null, "USD").getBalance())
                .isEqualByComparingTo(clearingBefore.add(new BigDecimal("30.00")));
    }

    @Test
    void releaseOrFinalizeWithoutAMatchingHoldIsRejected() {
        UUID merchantId = merchantWithBalance("80.00");
        UUID sagaId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();

        LedgerReply noHold = handler.handle(command(LedgerCommandType.FINALIZE_REFUND, sagaId, paymentId, merchantId, "30.00"));
        handler.handle(command(LedgerCommandType.HOLD_REFUND, sagaId, paymentId, merchantId, "30.00"));
        LedgerReply wrongAmount = handler.handle(command(LedgerCommandType.FINALIZE_REFUND, sagaId, paymentId, merchantId, "29.99"));

        assertThat(noHold.reason()).isEqualTo(LedgerCommandHandler.NO_ACTIVE_HOLD);
        assertThat(wrongAmount.reason()).isEqualTo(LedgerCommandHandler.NO_ACTIVE_HOLD);
        assertThat(balance(merchantId).getReserved()).isEqualByComparingTo("30.00");
    }

    @Test
    void auditQueriesListAPeriodsTransactionsAndOpenHolds() {
        java.time.LocalDateTime from = java.time.LocalDateTime.now().minusMinutes(1);
        UUID merchantId = merchantWithBalance("90.00");
        UUID openSaga = UUID.randomUUID();
        UUID openPayment = UUID.randomUUID();
        UUID doneSaga = UUID.randomUUID();
        UUID donePayment = UUID.randomUUID();
        handler.handle(command(LedgerCommandType.HOLD_REFUND, openSaga, openPayment, merchantId, "20.00"));
        handler.handle(command(LedgerCommandType.HOLD_REFUND, doneSaga, donePayment, merchantId, "30.00"));
        handler.handle(command(LedgerCommandType.FINALIZE_REFUND, doneSaga, donePayment, merchantId, "30.00"));
        java.time.LocalDateTime to = java.time.LocalDateTime.now().plusMinutes(1);

        List<com.shylesh.ledger_service.dto.LedgerTransactionSummary> period = transactionRepository
                .findSummariesBetween(from, to, org.springframework.data.domain.PageRequest.of(0, 1000)).getContent();
        List<com.shylesh.ledger_service.dto.LedgerTransactionSummary> open = transactionRepository.findOpenRefundHoldsCreatedBefore(to);

        assertThat(period).filteredOn(t -> t.paymentId().equals(donePayment))
                .extracting(t -> t.type().name()).containsExactly("REFUND_HOLD", "REFUND");
        assertThat(period).filteredOn(t -> t.paymentId().equals(donePayment))
                .allSatisfy(t -> assertThat(t.amount()).isEqualByComparingTo("30.00"));
        assertThat(open).extracting(com.shylesh.ledger_service.dto.LedgerTransactionSummary::paymentId)
                .contains(openPayment).doesNotContain(donePayment);
    }

    @Test
    void everyTransactionIsBalanced() {
        UUID merchantId = merchantWithBalance("40.00");
        UUID sagaId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        handler.handle(command(LedgerCommandType.HOLD_REFUND, sagaId, paymentId, merchantId, "15.00"));
        handler.handle(command(LedgerCommandType.FINALIZE_REFUND, sagaId, paymentId, merchantId, "15.00"));

        BigDecimal debits = BigDecimal.ZERO;
        BigDecimal credits = BigDecimal.ZERO;
        for (LedgerEntry entry : entryRepository.findAll()) {
            if (entry.getDirection() == LedgerDirection.DEBIT) {
                debits = debits.add(entry.getAmount());
            } else {
                credits = credits.add(entry.getAmount());
            }
        }
        assertThat(debits).isEqualByComparingTo(credits);
    }
}
