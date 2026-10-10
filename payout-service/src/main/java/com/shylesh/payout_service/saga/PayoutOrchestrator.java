package com.shylesh.payout_service.saga;

import com.shylesh.payout_service.client.BankClient;
import com.shylesh.payout_service.client.BankResponse;
import com.shylesh.payout_service.outbox.OutboxEventRepository;
import com.shylesh.payout_service.payout.Payout;
import com.shylesh.payout_service.payout.PayoutNotFoundException;
import com.shylesh.payout_service.payout.PayoutRepository;
import com.shylesh.payout_service.payout.PayoutStateException;
import com.shylesh.payout_service.payout.PayoutStatus;
import com.shylesh.payout_service.payout.PayoutTrigger;
import com.shylesh.payout_service.tracing.TraceContext;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Runs payout sagas: starts them, feeds them what happens (bank answers, ledger replies,
 * timers, operators), and applies the state machine's decisions.
 *
 * Every change to a saga happens in one transaction under its row lock, together with the
 * payout's status, the ledger command to send and the payout event to publish (both through the
 * outbox). Bank calls are the exception: they run outside any transaction, between a claim (the
 * step is leased to this worker and its attempt counted) and a record step (applied only if the
 * saga is still at that step and attempt; otherwise the answer is stale and dropped). The
 * transfer is sent under an Idempotency-Key derived from the saga, so sending it again is harmless.
 */
@Slf4j
@Service
public class PayoutOrchestrator {

    private final PayoutSagaRepository sagaRepository;
    private final PayoutSagaStepRepository stepRepository;
    private final PayoutRepository payoutRepository;
    private final PayoutSagaStateMachine stateMachine;
    private final BankClient bankClient;
    private final OutboxEventRepository outboxEventRepository;
    private final OutboxMessages messages;
    private final TraceContext traceContext;
    private final TransactionTemplate transactionTemplate;
    private final SagaProperties properties;
    private final MeterRegistry meterRegistry;

    public PayoutOrchestrator(PayoutSagaRepository sagaRepository, PayoutSagaStepRepository stepRepository,
                              PayoutRepository payoutRepository, PayoutSagaStateMachine stateMachine,
                              BankClient bankClient, OutboxEventRepository outboxEventRepository, OutboxMessages messages,
                              TraceContext traceContext, TransactionTemplate transactionTemplate,
                              SagaProperties properties, MeterRegistry meterRegistry) {
        this.sagaRepository = sagaRepository;
        this.stepRepository = stepRepository;
        this.payoutRepository = payoutRepository;
        this.stateMachine = stateMachine;
        this.bankClient = bankClient;
        this.outboxEventRepository = outboxEventRepository;
        this.messages = messages;
        this.traceContext = traceContext;
        this.transactionTemplate = transactionTemplate;
        this.properties = properties;
        this.meterRegistry = meterRegistry;

        Gauge.builder("sagas.requires_attention", sagaRepository, r -> r.countByState(SagaState.REQUIRES_ATTENTION))
                .description("Payout sagas parked for an operator")
                .register(meterRegistry);
        // Registered at 0 up front: a series that first appears at 1 makes increase() miss that
        // first payout, and the PayoutsFailing alert would never see a single failure.
        for (PayoutStatus status : List.of(PayoutStatus.IN_TRANSIT, PayoutStatus.PAID, PayoutStatus.FAILED, PayoutStatus.RETURNED)) {
            for (PayoutTrigger trigger : PayoutTrigger.values()) {
                transitions(status, trigger);
            }
        }
    }

    private Counter transitions(PayoutStatus status, PayoutTrigger trigger) {
        return Counter.builder("payouts.status.transitions").tag("status", status.name()).tag("trigger", trigger.name())
                .register(meterRegistry);
    }

    // ---------------------------------------------------------------- starting

    /** Starts the saga of a payout being created (call inside its transaction): the ledger is asked to hold the amount. */
    public PayoutSaga start(Payout payout) {
        LocalDateTime now = LocalDateTime.now();
        PayoutSaga saga = sagaRepository.save(PayoutSaga.start(payout.getId(), traceContext.current(), now));
        history(saga, SagaState.HOLDING, "STARTED", payout.getTrigger().name(), now);
        outboxEventRepository.save(messages.payoutEvent(payout, "PAYOUT_CREATED", saga.getTraceParent()));
        sendCommand(saga, payout, LedgerCommandType.HOLD_PAYOUT, now);
        Counter.builder("sagas.started").tag("type", "PAYOUT").register(meterRegistry).increment();
        return saga;
    }

    // ---------------------------------------------------------------- operator

    /** Operator: resume a parked saga at the step it stopped at. */
    @Transactional
    public PayoutSaga retry(UUID payoutId) {
        Payout payout = payoutRepository.findById(payoutId).orElseThrow(() -> new PayoutNotFoundException(payoutId));
        PayoutSaga saga = sagaRepository.findByPayoutIdForUpdate(payoutId)
                .filter(s -> s.getState() == SagaState.REQUIRES_ATTENTION)
                .orElseThrow(() -> new PayoutStateException("The payout has no saga waiting for an operator"));
        LocalDateTime now = LocalDateTime.now();
        saga.resume(now);
        history(saga, saga.getState(), "RESUMED", "Operator retry", now);
        if (saga.getState().kind() == StepKind.LEDGER_COMMAND) {
            // a parked ledger step was rejected; ask again under a new command id
            sendCommand(saga, payout, commandFor(saga.getState()), now);
        }
        log.warn("Payout saga resumed by operator. sagaId={}, payoutId={}, state={}", saga.getId(), payoutId, saga.getState());
        return saga;
    }

    // ---------------------------------------------------------------- ledger replies

    @Transactional
    public void onLedgerReply(LedgerReplyMessage reply) {
        Optional<PayoutSaga> found = sagaRepository.findByIdForUpdate(reply.sagaId());
        if (found.isEmpty()) {
            countStaleReply("unknown_saga");
            log.warn("Ledger reply for an unknown saga ignored. sagaId={}, commandId={}", reply.sagaId(), reply.commandId());
            return;
        }
        PayoutSaga saga = found.get();
        if (!saga.isActive() || saga.getState().kind() != StepKind.LEDGER_COMMAND
                || !reply.commandId().equals(saga.getPendingCommandId())) {
            // a duplicate, or the reply to a command that was re-sent and already answered
            countStaleReply("not_awaited");
            log.debug("Ledger reply not awaited, ignored. sagaId={}, state={}, commandId={}", saga.getId(), saga.getState(), reply.commandId());
            return;
        }
        Payout payout = payoutRepository.findById(saga.getPayoutId()).orElseThrow();
        LocalDateTime now = LocalDateTime.now();
        apply(saga, payout, stateMachine.decide(saga, new SagaInput.LedgerAnswer(reply.outcome(), reply.reason()), now),
                null, now);
    }

    // ---------------------------------------------------------------- worker

    /** What a worker needs to make a bank call for a claimed saga step. */
    record BankCall(UUID sagaId, SagaState state, int attempt, String traceParent, UUID payoutId, String bankAccount,
                    BigDecimal amount, String currency, String transferId) {
    }

    /** Runs a due saga's step (called by SagaWorker). */
    public void runDueStep(UUID sagaId) {
        Optional<BankCall> call = transactionTemplate.execute(status -> claim(sagaId));
        if (call == null || call.isEmpty()) {
            return;
        }
        BankCall claimed = call.get();
        BankResponse response = traceContext.continueTrace(claimed.traceParent(),
                "payout " + claimed.state().name().toLowerCase(), () -> callBank(claimed));
        transactionTemplate.executeWithoutResult(status -> record(claimed, response));
    }

    private Optional<BankCall> claim(UUID sagaId) {
        LocalDateTime now = LocalDateTime.now();
        Optional<PayoutSaga> locked = sagaRepository.lockIfDue(sagaId, now);
        if (locked.isEmpty()) {
            return Optional.empty();
        }
        PayoutSaga saga = locked.get();
        Payout payout = payoutRepository.findById(saga.getPayoutId()).orElseThrow();

        switch (saga.getState().kind()) {
            case BANK_CALL -> {
                saga.beginAttempt(now.plus(properties.lease()), now);
                return Optional.of(new BankCall(saga.getId(), saga.getState(), saga.getAttempt(), saga.getTraceParent(),
                        payout.getId(), payout.getBankAccount(), payout.getAmount(), payout.getCurrency(), payout.getTransferId()));
            }
            // a ledger reply is overdue: decided right here
            case LEDGER_COMMAND -> {
                apply(saga, payout, stateMachine.decide(saga, new SagaInput.TimerFired(), now), null, now);
                return Optional.empty();
            }
            default -> {
                saga.scheduleAt(null, now);
                return Optional.empty();
            }
        }
    }

    private BankResponse callBank(BankCall call) {
        return switch (call.state()) {
            // one key per saga: a transfer whose answer was lost is sent again, never twice
            case SUBMITTING -> bankClient.transfer(call.sagaId() + ":transfer", call.bankAccount(), call.amount(),
                    call.currency(), Payout.PUBLIC_ID_PREFIX + call.payoutId());
            case IN_TRANSIT, RETURN_WINDOW -> bankClient.status(call.transferId());
            default -> throw new IllegalStateException("No bank call in state " + call.state());
        };
    }

    private void record(BankCall call, BankResponse response) {
        PayoutSaga saga = sagaRepository.findByIdForUpdate(call.sagaId()).orElse(null);
        if (saga == null || !saga.isActive() || saga.getState() != call.state() || saga.getAttempt() != call.attempt()) {
            // our lease ran out and another worker took the step: its answer counts, not ours
            log.warn("Discarding stale bank answer. sagaId={}, state={}, attempt={}, answer={}",
                    call.sagaId(), call.state(), call.attempt(), response.describe());
            return;
        }
        Payout payout = payoutRepository.findById(saga.getPayoutId()).orElseThrow();
        LocalDateTime now = LocalDateTime.now();
        apply(saga, payout, stateMachine.decide(saga, new SagaInput.BankAnswer(response), now), response, now);
    }

    // ---------------------------------------------------------------- applying decisions

    private void apply(PayoutSaga saga, Payout payout, SagaDecision decision, BankResponse response, LocalDateTime now) {
        SagaState from = saga.getState();
        history(saga, from, decision.outcome(), decision.detail(), now);
        Counter.builder("saga.steps").tag("state", from.name()).tag("outcome", decision.outcome())
                .register(meterRegistry).increment();

        if (decision.parkReason() != null) {
            saga.park(decision.parkReason(), now);
            Counter.builder("sagas.parked").tag("type", "PAYOUT").tag("state", from.name())
                    .register(meterRegistry).increment();
            log.error("Payout saga needs attention. sagaId={}, payoutId={}, state={}, reason={}",
                    saga.getId(), saga.getPayoutId(), from, decision.parkReason());
            return;
        }

        if (decision.code() != null && decision.payoutChange() != PayoutChange.FAILED
                && decision.payoutChange() != PayoutChange.RETURNED) {
            // remembered for the payout change that follows (released -> FAILED, booked -> RETURNED)
            saga.recordFailureCode(decision.code());
        }

        changePayout(saga, payout, decision, response, now);

        if (decision.moveTo() != null) {
            if (decision.moveTo().isTerminal()) {
                saga.finish(decision.moveTo(), now);
                Counter.builder("sagas.finished").tag("type", "PAYOUT").tag("state", decision.moveTo().name())
                        .register(meterRegistry).increment();
                Timer.builder("saga.duration").tag("type", "PAYOUT").tag("state", decision.moveTo().name())
                        .register(meterRegistry).record(Duration.between(saga.getCreatedAt(), now));
                log.info("Payout saga finished. sagaId={}, payoutId={}, state={}", saga.getId(), saga.getPayoutId(), decision.moveTo());
                return;
            }
            saga.moveTo(decision.moveTo(), now);
        }

        if (decision.sendCommand() != null) {
            sendCommand(saga, payout, decision.sendCommand(), now);
            return;
        }
        if (decision.resendCommand()) {
            resendCommand(saga, payout, now);
            return;
        }
        switch (decision.schedule()) {
            case NOW -> saga.scheduleAt(now, now);
            case BACKOFF -> saga.retryLater(now.plus(backoff(saga.getAttempt())), decision.detail(), now);
            case TRANSIT_POLL -> saga.scheduleAt(now.plus(properties.transitPollInterval()), now);
            case RETURN_CHECK -> saga.scheduleAt(now.plus(properties.returnCheckInterval()), now);
            case NONE, REPLY_TIMEOUT -> saga.scheduleAt(null, now);
        }
    }

    private void changePayout(PayoutSaga saga, Payout payout, SagaDecision decision, BankResponse response, LocalDateTime now) {
        switch (decision.payoutChange()) {
            case NONE -> {
                return;
            }
            case IN_TRANSIT -> payout.markInTransit(response.transferId(), now);
            case PAID -> {
                payout.markPaid(now);
                publish(payout, "PAYOUT_PAID", saga);
            }
            case FAILED -> {
                payout.markFailed(decision.code(), now);
                publish(payout, "PAYOUT_FAILED", saga);
            }
            case RETURNED -> {
                payout.markReturned(decision.code(), now);
                publish(payout, "PAYOUT_RETURNED", saga);
            }
        }
        transitions(payout.getStatus(), payout.getTrigger()).increment();
    }

    private void publish(Payout payout, String eventType, PayoutSaga saga) {
        outboxEventRepository.save(messages.payoutEvent(payout, eventType, saga.getTraceParent()));
    }

    private void sendCommand(PayoutSaga saga, Payout payout, LedgerCommandType type, LocalDateTime now) {
        UUID commandId = UUID.randomUUID();
        outboxEventRepository.save(messages.ledgerCommand(saga, payout, type, commandId));
        saga.awaitReply(commandId, now.plus(replyTimeout(1)), now);
    }

    /** The reply is overdue: send the same command again (the ledger handles it once). */
    private void resendCommand(PayoutSaga saga, Payout payout, LocalDateTime now) {
        UUID commandId = saga.getPendingCommandId();
        outboxEventRepository.save(messages.ledgerCommand(saga, payout, commandFor(saga.getState()), commandId));
        saga.awaitReply(commandId, now.plus(replyTimeout(saga.getAttempt() + 1)), now);
        log.warn("Ledger reply overdue, command re-sent. sagaId={}, state={}, commandId={}, attempt={}",
                saga.getId(), saga.getState(), commandId, saga.getAttempt());
    }

    private static LedgerCommandType commandFor(SagaState state) {
        return switch (state) {
            case HOLDING -> LedgerCommandType.HOLD_PAYOUT;
            case RELEASING -> LedgerCommandType.RELEASE_PAYOUT;
            case FINALIZING -> LedgerCommandType.FINALIZE_PAYOUT;
            case RETURNING -> LedgerCommandType.RETURN_PAYOUT;
            default -> throw new IllegalStateException("No ledger command in state " + state);
        };
    }

    /** Exponential, capped, with +-20% jitter so sagas failing together don't retry in lockstep. */
    Duration backoff(int attempt) {
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

    private void history(PayoutSaga saga, SagaState state, String outcome, String detail, LocalDateTime now) {
        stepRepository.save(PayoutSagaStep.builder()
                .sagaId(saga.getId())
                .state(state)
                .outcome(outcome)
                .detail(detail == null ? null : detail.substring(0, Math.min(detail.length(), 1000)))
                .occurredAt(now)
                .build());
    }

    private void countStaleReply(String reason) {
        Counter.builder("saga.replies.ignored").tag("reason", reason).register(meterRegistry).increment();
    }
}
