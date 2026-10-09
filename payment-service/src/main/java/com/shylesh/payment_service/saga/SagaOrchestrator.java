package com.shylesh.payment_service.saga;

import com.shylesh.payment_service.common.outbox.OutboxEventFactory;
import com.shylesh.payment_service.common.outbox.OutboxEventRepository;
import com.shylesh.payment_service.common.tracing.TraceContext;
import com.shylesh.payment_service.entity.CaptureMethod;
import com.shylesh.payment_service.entity.Payment;
import com.shylesh.payment_service.entity.PaymentStatus;
import com.shylesh.payment_service.event.PaymentEventFactory;
import com.shylesh.payment_service.exception.InvalidPaymentStateException;
import com.shylesh.payment_service.exception.PaymentNotFoundException;
import com.shylesh.payment_service.processor.ProcessorClient;
import com.shylesh.payment_service.processor.ProcessorResponse;
import com.shylesh.payment_service.repository.PaymentRepository;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Runs the payment and refund sagas: starts them, feeds them what happens (processor answers,
 * ledger replies, timers, merchant actions), and applies the state machine's decisions.
 *
 * Every change to a saga happens in one transaction under its row lock, together with the
 * payment's status, the ledger command to send and the payment event to publish (both through
 * the outbox), so the saga, the payment and what the rest of the system is told never disagree.
 * Processor calls are the exception: they run outside any transaction, between a claim (the
 * step is leased to this worker and its attempt counted) and a record step (applied only if the
 * saga is still at that step and attempt; otherwise the answer is stale and dropped). Every
 * processor call uses an idempotency key derived from the saga and step, so a repeated call is
 * harmless.
 */
@Slf4j
@Service
public class SagaOrchestrator {

    private final PaymentSagaRepository sagaRepository;
    private final PaymentSagaStepRepository stepRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentSagaStateMachine stateMachine;
    private final ProcessorClient processorClient;
    private final OutboxEventRepository outboxEventRepository;
    private final OutboxEventFactory outboxEventFactory;
    private final PaymentEventFactory paymentEventFactory;
    private final TraceContext traceContext;
    private final TransactionTemplate transactionTemplate;
    private final SagaProperties properties;
    private final MeterRegistry meterRegistry;

    public SagaOrchestrator(PaymentSagaRepository sagaRepository, PaymentSagaStepRepository stepRepository,
                            PaymentRepository paymentRepository, PaymentSagaStateMachine stateMachine,
                            ProcessorClient processorClient, OutboxEventRepository outboxEventRepository,
                            OutboxEventFactory outboxEventFactory, PaymentEventFactory paymentEventFactory,
                            TraceContext traceContext, TransactionTemplate transactionTemplate,
                            SagaProperties properties, MeterRegistry meterRegistry) {
        this.sagaRepository = sagaRepository;
        this.stepRepository = stepRepository;
        this.paymentRepository = paymentRepository;
        this.stateMachine = stateMachine;
        this.processorClient = processorClient;
        this.outboxEventRepository = outboxEventRepository;
        this.outboxEventFactory = outboxEventFactory;
        this.paymentEventFactory = paymentEventFactory;
        this.traceContext = traceContext;
        this.transactionTemplate = transactionTemplate;
        this.properties = properties;
        this.meterRegistry = meterRegistry;

        Gauge.builder("sagas.requires_attention", sagaRepository, r -> r.countByState(SagaState.REQUIRES_ATTENTION))
                .description("Sagas parked for an operator")
                .register(meterRegistry);
    }

    // ---------------------------------------------------------------- starting

    /** Starts the payment saga for a payment being created (call inside its transaction). */
    public PaymentSaga startPayment(Payment payment) {
        LocalDateTime now = LocalDateTime.now();
        PaymentSaga saga = sagaRepository.save(
                PaymentSaga.start(payment.getId(), SagaType.PAYMENT, SagaState.AUTHORIZING, traceContext.current(), now));
        history(saga, SagaState.AUTHORIZING, "STARTED", payment.getCaptureMethod().name(), now);
        countStarted(SagaType.PAYMENT);
        return saga;
    }

    /**
     * Starts a refund of a successful payment: the payment becomes REFUND_PENDING and the ledger
     * is asked to hold the amount. The unique index on active sagas makes a second concurrent
     * refund of the same payment fail.
     */
    @Transactional
    public PaymentSaga startRefund(UUID merchantId, UUID paymentId) {
        Payment payment = paymentRepository.findByIdAndMerchantId(paymentId, merchantId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));
        if (payment.getStatus() != PaymentStatus.SUCCESS) {
            throw new InvalidPaymentStateException("Only a successful payment can be refunded; this one is " + payment.getStatus());
        }
        if (payment.getProcessorAuthorizationId() == null) {
            throw new InvalidPaymentStateException("This payment predates processor integration and can't be refunded through the API");
        }
        if (sagaRepository.findActiveByPaymentIdForUpdate(paymentId).isPresent()) {
            throw new InvalidPaymentStateException("A refund of this payment is already in progress");
        }
        LocalDateTime now = LocalDateTime.now();
        payment.markRefundPending();
        countTransition(PaymentStatus.REFUND_PENDING);
        PaymentSaga saga = sagaRepository.saveAndFlush(
                PaymentSaga.start(payment.getId(), SagaType.REFUND, SagaState.HOLDING, traceContext.current(), now));
        history(saga, SagaState.HOLDING, "STARTED", null, now);
        sendCommand(saga, payment, LedgerCommandType.HOLD_REFUND, now);
        countStarted(SagaType.REFUND);
        return saga;
    }

    // ---------------------------------------------------------------- merchant and operator actions

    /** MANUAL capture: only while the authorization is waiting for it. */
    @Transactional
    public void requestCapture(UUID merchantId, UUID paymentId) {
        merchantAction(merchantId, paymentId, "captured", new SagaInput.CaptureRequested());
    }

    /** Cancel an uncaptured (MANUAL) authorization: it is voided, then the payment is CANCELLED. */
    @Transactional
    public void requestCancel(UUID merchantId, UUID paymentId) {
        merchantAction(merchantId, paymentId, "cancelled", new SagaInput.CancelRequested());
    }

    private void merchantAction(UUID merchantId, UUID paymentId, String action, SagaInput input) {
        Payment payment = paymentRepository.findByIdAndMerchantId(paymentId, merchantId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));
        Optional<PaymentSaga> active = sagaRepository.findActiveByPaymentIdForUpdate(paymentId);
        if (active.isEmpty() || active.get().getState() != SagaState.AWAITING_CAPTURE) {
            throw new InvalidPaymentStateException("Only an authorized, uncaptured payment (MANUAL capture) can be "
                    + action + "; this one is " + payment.getStatus());
        }
        PaymentSaga saga = active.get();
        LocalDateTime now = LocalDateTime.now();
        apply(saga, payment, stateMachine.decide(saga, payment.getCaptureMethod(), input, now), null, now);
    }

    /** Operator: resume a parked saga at the step it stopped at. */
    @Transactional
    public PaymentSaga retry(UUID paymentId) {
        Payment payment = paymentRepository.findById(paymentId).orElseThrow(() -> new PaymentNotFoundException(paymentId));
        PaymentSaga saga = sagaRepository.findActiveByPaymentIdForUpdate(paymentId)
                .filter(s -> s.getState() == SagaState.REQUIRES_ATTENTION)
                .orElseThrow(() -> new InvalidPaymentStateException("The payment has no saga waiting for an operator"));
        LocalDateTime now = LocalDateTime.now();
        saga.resume(now);
        history(saga, saga.getState(), "RESUMED", "Operator retry", now);
        if (saga.getState().kind() == StepKind.LEDGER_COMMAND) {
            // a parked ledger step was rejected; ask again under a new command id
            sendCommand(saga, payment, commandFor(saga.getState()), now);
        }
        log.warn("Saga resumed by operator. sagaId={}, paymentId={}, state={}", saga.getId(), paymentId, saga.getState());
        return saga;
    }

    // ---------------------------------------------------------------- ledger replies

    @Transactional
    public void onLedgerReply(LedgerReplyMessage reply) {
        Optional<PaymentSaga> found = sagaRepository.findByIdForUpdate(reply.sagaId());
        if (found.isEmpty()) {
            countStaleReply("unknown_saga");
            log.warn("Ledger reply for an unknown saga ignored. sagaId={}, commandId={}", reply.sagaId(), reply.commandId());
            return;
        }
        PaymentSaga saga = found.get();
        if (!saga.isActive() || saga.getState().kind() != StepKind.LEDGER_COMMAND
                || !reply.commandId().equals(saga.getPendingCommandId())) {
            // a duplicate, or the reply to a command that was re-sent and already answered
            countStaleReply("not_awaited");
            log.debug("Ledger reply not awaited, ignored. sagaId={}, state={}, commandId={}", saga.getId(), saga.getState(), reply.commandId());
            return;
        }
        Payment payment = paymentRepository.findById(saga.getPaymentId()).orElseThrow();
        LocalDateTime now = LocalDateTime.now();
        apply(saga, payment, stateMachine.decide(saga, payment.getCaptureMethod(),
                new SagaInput.LedgerAnswer(reply.outcome(), reply.reason()), now), null, now);
    }

    // ---------------------------------------------------------------- worker

    /** What a worker needs to make a processor call for a claimed saga step. */
    record ProcessorCall(UUID sagaId, SagaState state, int attempt, String traceParent,
                         UUID paymentId, String paymentMethod, java.math.BigDecimal amount, String currency,
                         String authorizationId) {
    }

    /** Runs a due saga's step (called by SagaWorker). */
    public void runDueStep(UUID sagaId) {
        Optional<ProcessorCall> call = transactionTemplate.execute(status -> claim(sagaId));
        if (call == null || call.isEmpty()) {
            return;
        }
        ProcessorCall claimed = call.get();
        ProcessorResponse response = traceContext.continueTrace(claimed.traceParent(),
                "saga " + claimed.state().name().toLowerCase(), () -> callProcessor(claimed));
        transactionTemplate.executeWithoutResult(status -> record(claimed, response));
    }

    private Optional<ProcessorCall> claim(UUID sagaId) {
        LocalDateTime now = LocalDateTime.now();
        Optional<PaymentSaga> locked = sagaRepository.lockIfDue(sagaId, now);
        if (locked.isEmpty()) {
            return Optional.empty();
        }
        PaymentSaga saga = locked.get();
        Payment payment = paymentRepository.findById(saga.getPaymentId()).orElseThrow();

        switch (saga.getState().kind()) {
            case PROCESSOR_CALL -> {
                saga.beginAttempt(now.plus(properties.lease()), now);
                return Optional.of(new ProcessorCall(saga.getId(), saga.getState(), saga.getAttempt(), saga.getTraceParent(),
                        payment.getId(), payment.getPaymentMethod(), payment.getAmount(), payment.getCurrency(),
                        payment.getProcessorAuthorizationId()));
            }
            // a ledger reply is overdue, or a MANUAL authorization expired: decided right here
            case LEDGER_COMMAND, WAIT_FOR_MERCHANT -> {
                apply(saga, payment, stateMachine.decide(saga, payment.getCaptureMethod(), new SagaInput.TimerFired(), now), null, now);
                return Optional.empty();
            }
            default -> {
                saga.scheduleAt(null, now);
                return Optional.empty();
            }
        }
    }

    private ProcessorResponse callProcessor(ProcessorCall call) {
        // keys: one per saga step, so retries of a step are idempotent and steps never collide
        String keyPrefix = call.sagaId() + ":";
        return switch (call.state()) {
            case AUTHORIZING -> processorClient.authorize(keyPrefix + "authorize", call.paymentMethod(),
                    call.amount(), call.currency(), "pay_" + call.paymentId());
            case CAPTURING -> processorClient.capture(keyPrefix + "capture", call.authorizationId());
            // reverse by this saga's authorize request key: works even if we never learned the outcome
            case VOIDING -> processorClient.reverse(keyPrefix + "reverse", call.sagaId() + ":authorize");
            case REFUNDING -> processorClient.refund(keyPrefix + "refund", call.authorizationId(), call.amount());
            default -> throw new IllegalStateException("No processor call in state " + call.state());
        };
    }

    private void record(ProcessorCall call, ProcessorResponse response) {
        PaymentSaga saga = sagaRepository.findByIdForUpdate(call.sagaId()).orElse(null);
        if (saga == null || !saga.isActive() || saga.getState() != call.state() || saga.getAttempt() != call.attempt()) {
            // our lease ran out and another worker took the step: its answer counts, not ours
            log.warn("Discarding stale processor answer. sagaId={}, state={}, attempt={}, answer={}",
                    call.sagaId(), call.state(), call.attempt(), response.describe());
            return;
        }
        Payment payment = paymentRepository.findById(saga.getPaymentId()).orElseThrow();
        LocalDateTime now = LocalDateTime.now();
        apply(saga, payment, stateMachine.decide(saga, payment.getCaptureMethod(),
                new SagaInput.ProcessorAnswer(response), now), response.id(), now);
    }

    // ---------------------------------------------------------------- applying decisions

    private void apply(PaymentSaga saga, Payment payment, SagaDecision decision, String processorId, LocalDateTime now) {
        SagaState from = saga.getState();
        history(saga, from, decision.outcome(), decision.detail(), now);
        Counter.builder("saga.steps").tag("state", from.name()).tag("outcome", decision.outcome())
                .register(meterRegistry).increment();

        if (decision.parkReason() != null) {
            saga.park(decision.parkReason(), now);
            Counter.builder("sagas.parked").tag("type", saga.getType().name()).tag("state", from.name())
                    .register(meterRegistry).increment();
            log.error("Saga needs attention. sagaId={}, paymentId={}, state={}, reason={}",
                    saga.getId(), saga.getPaymentId(), from, decision.parkReason());
            return;
        }

        if (decision.compensateTo() != null) {
            saga.compensate(decision.compensateTo(), decision.code());
        } else if (decision.code() != null && decision.paymentChange() == PaymentChange.NONE) {
            saga.recordFailureCode(decision.code());
        }

        changePayment(saga, payment, decision, processorId, now);

        if (decision.moveTo() != null) {
            if (decision.moveTo().isTerminal()) {
                saga.finish(decision.moveTo(), now);
                Counter.builder("sagas.finished").tag("type", saga.getType().name()).tag("state", decision.moveTo().name())
                        .register(meterRegistry).increment();
                Timer.builder("saga.duration").tag("type", saga.getType().name()).tag("state", decision.moveTo().name())
                        .register(meterRegistry).record(Duration.between(saga.getCreatedAt(), now));
                log.info("Saga finished. sagaId={}, paymentId={}, type={}, state={}",
                        saga.getId(), saga.getPaymentId(), saga.getType(), decision.moveTo());
                return;
            }
            saga.moveTo(decision.moveTo(), now);
        }

        if (decision.sendCommand() != null) {
            sendCommand(saga, payment, decision.sendCommand(), now);
            return;
        }
        if (decision.resendCommand()) {
            resendCommand(saga, payment, now);
            return;
        }
        switch (decision.schedule()) {
            case NOW -> saga.scheduleAt(now, now);
            case BACKOFF -> saga.retryLater(now.plus(processorBackoff(saga.getAttempt())), decision.detail(), now);
            case AUTHORIZATION_EXPIRY -> saga.scheduleAt(payment.getAuthorizationExpiresAt(), now);
            case NONE, REPLY_TIMEOUT -> saga.scheduleAt(null, now);
        }
    }

    private void changePayment(PaymentSaga saga, Payment payment, SagaDecision decision, String processorId, LocalDateTime now) {
        String code = decision.code();
        switch (decision.paymentChange()) {
            case NONE -> {
                return;
            }
            case AUTHORIZED -> {
                payment.recordAuthorization(processorId, now.plus(properties.authorizationTtl()));
                if (payment.getCaptureMethod() == CaptureMethod.MANUAL) {
                    publish(payment, "PAYMENT_AUTHORIZED", null, saga);
                }
            }
            case CAPTURE_REQUESTED -> payment.markCaptureRequested();
            case SUCCEEDED -> {
                payment.markSucceeded();
                publish(payment, "PAYMENT_COMPLETED", null, saga);
            }
            case FAILED -> {
                payment.markFailed(code);
                publish(payment, "PAYMENT_FAILED", code, saga);
            }
            case CANCELLED -> {
                payment.markCancelled(code);
                publish(payment, "PAYMENT_CANCELLED", code, saga);
            }
            case REFUNDED -> {
                payment.markRefunded();
                publish(payment, "PAYMENT_REFUNDED", null, saga);
            }
            case REFUND_FAILED -> {
                payment.markRefundFailed(code);
                publish(payment, "PAYMENT_REFUND_FAILED", code, saga);
            }
        }
        countTransition(payment.getStatus());
    }

    private void publish(Payment payment, String eventType, String failureCode, PaymentSaga saga) {
        outboxEventRepository.save(outboxEventFactory.createPaymentEvent(
                paymentEventFactory.create(payment, failureCode), eventType, saga.getTraceParent()));
    }

    private void sendCommand(PaymentSaga saga, Payment payment, LedgerCommandType type, LocalDateTime now) {
        UUID commandId = UUID.randomUUID();
        writeCommand(saga, payment, type, commandId);
        saga.awaitReply(commandId, now.plus(replyTimeout(1)), now);
    }

    /** The reply is overdue: send the same command again (the ledger handles it once). */
    private void resendCommand(PaymentSaga saga, Payment payment, LocalDateTime now) {
        UUID commandId = saga.getPendingCommandId();
        writeCommand(saga, payment, commandFor(saga.getState()), commandId);
        saga.awaitReply(commandId, now.plus(replyTimeout(saga.getAttempt() + 1)), now);
        log.warn("Ledger reply overdue, command re-sent. sagaId={}, state={}, commandId={}, attempt={}",
                saga.getId(), saga.getState(), commandId, saga.getAttempt());
    }

    private void writeCommand(PaymentSaga saga, Payment payment, LedgerCommandType type, UUID commandId) {
        outboxEventRepository.save(outboxEventFactory.createLedgerCommand(new LedgerCommandMessage(
                commandId, type.name(), saga.getId(), payment.getId(), payment.getMerchantId(),
                payment.getAmount(), payment.getCurrency()), saga.getTraceParent()));
    }

    private static LedgerCommandType commandFor(SagaState state) {
        return switch (state) {
            case SETTLING -> LedgerCommandType.SETTLE_PAYMENT;
            case HOLDING -> LedgerCommandType.HOLD_REFUND;
            case RELEASING -> LedgerCommandType.RELEASE_HOLD;
            case FINALIZING -> LedgerCommandType.FINALIZE_REFUND;
            default -> throw new IllegalStateException("No ledger command in state " + state);
        };
    }

    /** Exponential, capped, with +-20% jitter so sagas failing together don't retry in lockstep. */
    Duration processorBackoff(int attempt) {
        Duration delay = properties.retry().initialBackoff().multipliedBy(1L << Math.min(Math.max(attempt - 1, 0), 20));
        if (delay.compareTo(properties.retry().maxBackoff()) > 0) {
            delay = properties.retry().maxBackoff();
        }
        double jitter = 0.8 + ThreadLocalRandom.current().nextDouble() * 0.4;
        return Duration.ofMillis((long) (delay.toMillis() * jitter));
    }

    Duration replyTimeout(int attempt) {
        Duration timeout = properties.replies().timeout().multipliedBy(1L << Math.min(Math.max(attempt - 1, 0), 20));
        return timeout.compareTo(properties.replies().maxTimeout()) > 0 ? properties.replies().maxTimeout() : timeout;
    }

    private void history(PaymentSaga saga, SagaState state, String outcome, String detail, LocalDateTime now) {
        stepRepository.save(PaymentSagaStep.builder()
                .sagaId(saga.getId())
                .state(state)
                .outcome(outcome)
                .detail(detail == null ? null : detail.substring(0, Math.min(detail.length(), 1000)))
                .occurredAt(now)
                .build());
    }

    private void countStarted(SagaType type) {
        Counter.builder("sagas.started").tag("type", type.name()).register(meterRegistry).increment();
    }

    private void countStaleReply(String reason) {
        Counter.builder("saga.replies.ignored").tag("reason", reason).register(meterRegistry).increment();
    }

    private void countTransition(PaymentStatus status) {
        Counter.builder("payments.status.transitions").tag("status", status.name()).register(meterRegistry).increment();
    }
}
