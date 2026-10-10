package com.shylesh.payment_service.saga;

import com.shylesh.payment_service.common.outbox.OutboxEvent;
import com.shylesh.payment_service.common.outbox.OutboxEventRepository;
import com.shylesh.payment_service.dto.CreatePaymentRequest;
import com.shylesh.payment_service.dto.PaymentResponse;
import com.shylesh.payment_service.entity.CaptureMethod;
import com.shylesh.payment_service.entity.Payment;
import com.shylesh.payment_service.entity.PaymentStatus;
import com.shylesh.payment_service.exception.InvalidPaymentStateException;
import com.shylesh.payment_service.repository.PaymentRepository;
import com.shylesh.payment_service.service.PaymentService;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * The orchestrator end to end against real Postgres, Kafka and Redis, with a stub card
 * processor (HTTP) and a stub ledger (consuming ledger-commands, replying on ledger-replies).
 * Timeouts are shrunk to seconds so expiry, re-sends and deadlines happen within the test.
 */
@SpringBootTest(properties = {
        "management.tracing.enabled=false",
        "payflow.processor.api-key=test-key",
        "payflow.processor.read-timeout=1s",
        "payflow.saga.poll-interval=100ms",
        "payflow.saga.authorization-ttl=4s",
        "payflow.saga.step-timeouts.authorize=3s",
        "payflow.saga.retry.initial-backoff=100ms",
        "payflow.saga.retry.max-backoff=500ms",
        "payflow.saga.replies.timeout=2s",
        "payflow.saga.replies.max-timeout=4s",
        "outbox.publisher.poll-interval=PT0.1S",
        "resilience4j.circuitbreaker.instances.processor.sliding-window-size=4",
        "resilience4j.circuitbreaker.instances.processor.minimum-number-of-calls=4",
        "resilience4j.circuitbreaker.instances.processor.wait-duration-in-open-state=1s"
})
@Testcontainers
class SagaIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:4.2.0");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static StubProcessor processor;
    static StubLedger ledger;

    @BeforeAll
    static void startStubs() throws IOException {
        processor = new StubProcessor();
    }

    @DynamicPropertySource
    static void processorUrl(DynamicPropertyRegistry registry) {
        registry.add("payflow.processor.base-url", () -> processor.baseUrl());
    }

    @AfterAll
    static void stopStubs() {
        processor.stop();
        if (ledger != null) {
            ledger.close();
        }
    }

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private PaymentSagaRepository sagaRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    private io.micrometer.core.instrument.MeterRegistry meterRegistry;

    private final UUID merchantId = UUID.randomUUID();

    private StubLedger ledger() {
        if (ledger == null) {
            ledger = new StubLedger(kafka.getBootstrapServers());
        }
        return ledger;
    }

    @AfterEach
    void reset() {
        processor.endOutage();
        ledger().resumeReplies();
        ledger().alwaysSucceed();
    }

    private UUID pay(String paymentMethod, CaptureMethod captureMethod) {
        ledger();
        PaymentResponse response = paymentService.createPayment(merchantId, UUID.randomUUID().toString(),
                CreatePaymentRequest.builder().amount(new BigDecimal("40.00")).currency("USD")
                        .paymentMethod(paymentMethod).captureMethod(captureMethod).build());
        assertThat(response.getStatus()).isEqualTo("PROCESSING");
        return UUID.fromString(response.getPaymentId().substring("pay_".length()));
    }

    private Payment awaitStatus(UUID paymentId, PaymentStatus status) {
        await().atMost(Duration.ofSeconds(25)).pollInterval(Duration.ofMillis(100))
                .until(() -> paymentRepository.findById(paymentId).orElseThrow().getStatus() == status);
        return paymentRepository.findById(paymentId).orElseThrow();
    }

    private List<String> events(UUID paymentId) {
        return outboxEventRepository.findAll().stream()
                .filter(e -> e.getAggregateId().equals(paymentId) && e.getTopic().equals("payment-created"))
                .sorted((a, b) -> Long.compare(a.getSeq(), b.getSeq()))
                .map(OutboxEvent::getEventType)
                .toList();
    }

    private PaymentSaga saga(UUID paymentId, SagaType type) {
        return sagaRepository.findByPaymentIdOrderByCreatedAtAsc(paymentId).stream()
                .filter(s -> s.getType() == type).reduce((first, second) -> second).orElseThrow();
    }

    private List<String> commandTypes(UUID paymentId) {
        return ledger().commandsFor(paymentId).stream().map(StubLedger.Command::commandType).toList();
    }

    private UUID succeeded(String paymentMethod) {
        UUID paymentId = pay(paymentMethod, CaptureMethod.AUTOMATIC);
        awaitStatus(paymentId, PaymentStatus.SUCCESS);
        return paymentId;
    }

    @Test
    void automaticPaymentIsAuthorizedCapturedAndSettledOnce() {
        UUID paymentId = succeeded("pm_card_visa");
        PaymentSaga saga = saga(paymentId, SagaType.PAYMENT);

        assertThat(saga.getState()).isEqualTo(SagaState.COMPLETED);
        assertThat(processor.callsWithKey(saga.getId() + ":")).extracting(StubProcessor.Call::idempotencyKey)
                .containsExactly(saga.getId() + ":authorize", saga.getId() + ":capture");
        assertThat(commandTypes(paymentId)).containsExactly("SETTLE_PAYMENT");
        assertThat(events(paymentId)).containsExactly("PAYMENT_CREATED", "PAYMENT_COMPLETED");
        assertThat(paymentRepository.findById(paymentId).orElseThrow().getProcessorAuthorizationId()).startsWith("auth_");
    }

    @Test
    void auditLookupShowsWhetherASagaIsStillRunning() {
        ledger().pauseReplies();
        UUID paymentId = pay("pm_card_visa", CaptureMethod.AUTOMATIC);
        await().atMost(Duration.ofSeconds(10)).until(() -> saga(paymentId, SagaType.PAYMENT).getState() == SagaState.SETTLING);

        assertThat(paymentService.lookupPayments(List.of(paymentId)).getFirst().sagaActive()).isTrue();
        await().atMost(Duration.ofSeconds(5)).until(() ->
                meterRegistry.get("sagas.oldest.step.age").tag("type", "PAYMENT").gauge().value() >= 1);
        assertThat(meterRegistry.get("outbox.pending").gauge().value()).isGreaterThanOrEqualTo(0);

        ledger().resumeReplies();
        awaitStatus(paymentId, PaymentStatus.SUCCESS);
        var view = paymentService.lookupPayments(List.of(paymentId, UUID.randomUUID()));
        assertThat(view).hasSize(1);
        assertThat(view.getFirst().sagaActive()).isFalse();
        assertThat(view.getFirst().processorBacked()).isTrue();
        assertThat(view.getFirst().status()).isEqualTo("SUCCESS");
        assertThat(view.getFirst().paymentId()).isEqualTo("pay_" + paymentId);
    }

    @Test
    void declinedAuthorizationFailsWithTheIssuerCodeAndNothingToUndo() {
        UUID paymentId = pay("pm_card_declined", CaptureMethod.AUTOMATIC);

        Payment payment = awaitStatus(paymentId, PaymentStatus.FAILED);

        assertThat(payment.getFailureCode()).isEqualTo("do_not_honor");
        assertThat(processor.callsWithKey(saga(paymentId, SagaType.PAYMENT).getId() + ":")).hasSize(1);
        assertThat(commandTypes(paymentId)).isEmpty();
        assertThat(events(paymentId)).containsExactly("PAYMENT_CREATED", "PAYMENT_FAILED");
    }

    @Test
    void declinedCaptureIsCompensatedByReversingTheAuthorization() {
        UUID paymentId = pay("pm_card_capture_fails", CaptureMethod.AUTOMATIC);

        Payment payment = awaitStatus(paymentId, PaymentStatus.FAILED);
        PaymentSaga saga = saga(paymentId, SagaType.PAYMENT);

        assertThat(payment.getFailureCode()).isEqualTo("capture_declined");
        List<StubProcessor.Call> reversals = processor.callsFor("/v1/reversals").stream()
                .filter(c -> c.idempotencyKey().equals(saga.getId() + ":reverse")).toList();
        assertThat(reversals).hasSize(1);
        assertThat(reversals.getFirst().body()).contains(saga.getId() + ":authorize");
        assertThat(commandTypes(paymentId)).isEmpty();
    }

    @Test
    void manualPaymentWaitsAuthorizedUntilTheMerchantCapturesIt() {
        UUID paymentId = pay("pm_card_visa", CaptureMethod.MANUAL);
        awaitStatus(paymentId, PaymentStatus.AUTHORIZED);

        paymentService.capturePayment(merchantId, paymentId);

        awaitStatus(paymentId, PaymentStatus.SUCCESS);
        assertThat(events(paymentId)).containsExactly("PAYMENT_CREATED", "PAYMENT_AUTHORIZED", "PAYMENT_COMPLETED");
    }

    @Test
    void manualPaymentCanBeCancelledAndIsVoided() {
        UUID paymentId = pay("pm_card_visa", CaptureMethod.MANUAL);
        awaitStatus(paymentId, PaymentStatus.AUTHORIZED);

        paymentService.cancelPayment(merchantId, paymentId);

        awaitStatus(paymentId, PaymentStatus.CANCELLED);
        assertThat(processor.callsWithKey(saga(paymentId, SagaType.PAYMENT).getId() + ":reverse")).hasSize(1);
        assertThat(events(paymentId)).containsExactly("PAYMENT_CREATED", "PAYMENT_AUTHORIZED", "PAYMENT_CANCELLED");
        assertThatThrownBy(() -> paymentService.capturePayment(merchantId, paymentId))
                .isInstanceOf(InvalidPaymentStateException.class);
    }

    @Test
    void uncapturedAuthorizationExpiresAndIsVoided() {
        UUID paymentId = pay("pm_card_visa", CaptureMethod.MANUAL);
        awaitStatus(paymentId, PaymentStatus.AUTHORIZED);

        Payment payment = awaitStatus(paymentId, PaymentStatus.CANCELLED);

        assertThat(payment.getFailureCode()).isEqualTo(PaymentSagaStateMachine.AUTHORIZATION_EXPIRED);
    }

    @Test
    void answerLostToATimeoutIsRecoveredByRetryingWithTheSameKey() {
        UUID paymentId = succeeded("pm_card_timeout_once");
        PaymentSaga saga = saga(paymentId, SagaType.PAYMENT);

        List<StubProcessor.Call> authorizeCalls = processor.callsFor("/v1/authorizations").stream()
                .filter(c -> c.idempotencyKey().equals(saga.getId() + ":authorize")).toList();
        assertThat(authorizeCalls).hasSizeGreaterThanOrEqualTo(2);
        assertThat(processor.callsWithKey(saga.getId() + ":reverse")).isEmpty();
    }

    @Test
    void processorDownPastTheAuthorizationDeadlineReversesAndFailsTheOpenCircuitRecovers() {
        processor.outageFor(5_000);
        UUID paymentId = pay("pm_card_visa", CaptureMethod.AUTOMATIC);

        await().atMost(Duration.ofSeconds(10)).until(() ->
                circuitBreakerRegistry.circuitBreaker("processor").getState() == CircuitBreaker.State.OPEN);
        Payment payment = awaitStatus(paymentId, PaymentStatus.FAILED);

        assertThat(payment.getFailureCode()).isEqualTo(PaymentSagaStateMachine.PROCESSOR_UNAVAILABLE);
        assertThat(processor.callsWithKey(saga(paymentId, SagaType.PAYMENT).getId() + ":reverse")).isNotEmpty();
        // once the processor is back, new payments go through again
        succeeded("pm_card_visa");
    }

    @Test
    void overdueLedgerReplyIsAnsweredByResendingTheSameCommand() {
        ledger().pauseReplies();
        UUID paymentId = pay("pm_card_visa", CaptureMethod.AUTOMATIC);
        await().atMost(Duration.ofSeconds(15)).until(() -> ledger().commandsFor(paymentId).size() >= 2);

        ledger().resumeReplies();
        awaitStatus(paymentId, PaymentStatus.SUCCESS);

        Set<UUID> commandIds = ledger().commandsFor(paymentId).stream().map(StubLedger.Command::commandId).collect(Collectors.toSet());
        assertThat(commandIds).hasSize(1);
        assertThat(events(paymentId)).containsExactly("PAYMENT_CREATED", "PAYMENT_COMPLETED");
    }

    @Test
    void repliesToCommandsTheSagaIsNotWaitingForAreIgnored() {
        UUID paymentId = succeeded("pm_card_visa");
        PaymentSaga saga = saga(paymentId, SagaType.PAYMENT);
        UUID commandId = ledger().commandsFor(paymentId).getFirst().commandId();

        ledger().reply(paymentId, saga.getId(), commandId, "SETTLE_PAYMENT", "SUCCEEDED", null);
        ledger().reply(paymentId, saga.getId(), UUID.randomUUID(), "SETTLE_PAYMENT", "REJECTED", "WHATEVER");

        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(4)).until(() ->
                saga(paymentId, SagaType.PAYMENT).getState() == SagaState.COMPLETED);
        assertThat(events(paymentId)).containsExactly("PAYMENT_CREATED", "PAYMENT_COMPLETED");
    }

    @Test
    void refundHoldsAtTheLedgerRefundsAtTheProcessorAndFinalizes() {
        UUID paymentId = succeeded("pm_card_visa");

        PaymentResponse pending = paymentService.refundPayment(merchantId, paymentId);
        awaitStatus(paymentId, PaymentStatus.REFUNDED);

        assertThat(pending.getStatus()).isEqualTo("REFUND_PENDING");
        assertThat(commandTypes(paymentId)).containsExactly("SETTLE_PAYMENT", "HOLD_REFUND", "FINALIZE_REFUND");
        assertThat(processor.callsWithKey(saga(paymentId, SagaType.REFUND).getId() + ":refund")).hasSize(1);
        assertThat(events(paymentId)).containsExactly("PAYMENT_CREATED", "PAYMENT_COMPLETED", "PAYMENT_REFUNDED");
    }

    @Test
    void refundBeyondTheMerchantBalanceFailsAndThePaymentStaysRefundable() {
        UUID paymentId = succeeded("pm_card_visa");
        ledger().decideWith(command -> command.commandType().equals("HOLD_REFUND")
                ? new String[]{"REJECTED", "INSUFFICIENT_FUNDS"} : new String[]{"SUCCEEDED", null});

        paymentService.refundPayment(merchantId, paymentId);
        await().atMost(Duration.ofSeconds(15)).until(() -> saga(paymentId, SagaType.REFUND).getState() == SagaState.REFUND_FAILED);

        Payment payment = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(payment.getRefundFailureCode()).isEqualTo(PaymentSagaStateMachine.INSUFFICIENT_BALANCE);
        assertThat(processor.callsFor("/v1/refunds").stream()
                .filter(c -> c.body().contains(payment.getProcessorAuthorizationId())).toList()).isEmpty();
        assertThat(events(paymentId)).endsWith("PAYMENT_REFUND_FAILED");

        // the merchant can try again later
        ledger().alwaysSucceed();
        paymentService.refundPayment(merchantId, paymentId);
        awaitStatus(paymentId, PaymentStatus.REFUNDED);
    }

    @Test
    void refundRefusedByTheProcessorReleasesTheHold() {
        UUID paymentId = succeeded("pm_card_refund_fails");

        paymentService.refundPayment(merchantId, paymentId);
        await().atMost(Duration.ofSeconds(15)).until(() -> saga(paymentId, SagaType.REFUND).getState() == SagaState.REFUND_FAILED);

        Payment payment = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(payment.getRefundFailureCode()).isEqualTo("refund_declined");
        assertThat(commandTypes(paymentId)).containsExactly("SETTLE_PAYMENT", "HOLD_REFUND", "RELEASE_HOLD");
    }

    @Test
    void onlyOneRefundAtATime() {
        UUID paymentId = succeeded("pm_card_visa");
        ledger().pauseReplies();

        paymentService.refundPayment(merchantId, paymentId);

        assertThatThrownBy(() -> paymentService.refundPayment(merchantId, paymentId))
                .isInstanceOf(InvalidPaymentStateException.class);
        ledger().resumeReplies();
        awaitStatus(paymentId, PaymentStatus.REFUNDED);
    }

    @Test
    void rejectedSettlementIsParkedAndAnOperatorRetryCompletesIt() {
        ledger().decideWith(command -> command.commandType().equals("SETTLE_PAYMENT")
                ? new String[]{"REJECTED", "SOMETHING_WRONG"} : new String[]{"SUCCEEDED", null});
        UUID paymentId = pay("pm_card_visa", CaptureMethod.AUTOMATIC);
        await().atMost(Duration.ofSeconds(15)).until(() -> saga(paymentId, SagaType.PAYMENT).getState() == SagaState.REQUIRES_ATTENTION);

        assertThat(paymentRepository.findById(paymentId).orElseThrow().getStatus()).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(saga(paymentId, SagaType.PAYMENT).getStuckState()).isEqualTo(SagaState.SETTLING);

        ledger().alwaysSucceed();
        paymentService.retrySaga(paymentId);

        awaitStatus(paymentId, PaymentStatus.SUCCESS);
        assertThat(ledger().commandsFor(paymentId).stream().map(StubLedger.Command::commandId).distinct()).hasSize(2);
    }
}
