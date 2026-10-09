package com.shylesh.payment_service.saga;

import com.shylesh.payment_service.entity.CaptureMethod;
import com.shylesh.payment_service.entity.PaymentStatus;
import com.shylesh.payment_service.processor.ProcessorResponse;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * The payment and refund sagas as a pure function: (current state, what happened) -> decision.
 * No I/O, so every transition, compensation and timeout rule is unit-tested directly.
 *
 * Payment: AUTHORIZING -> [MANUAL: AWAITING_CAPTURE] -> CAPTURING -> SETTLING -> COMPLETED.
 *   Before the capture (the pivot) any failure is compensated by VOIDING the authorization;
 *   once the capture may have happened, steps are only retried, and an outcome that stays
 *   unknown parks the saga for an operator instead of guessing.
 * Refund: HOLDING -> REFUNDING -> FINALIZING -> REFUNDED; a refused refund is compensated by
 *   RELEASING the hold (-> REFUND_FAILED).
 */
@Component
@RequiredArgsConstructor
public class PaymentSagaStateMachine {

    public static final String PROCESSOR_UNAVAILABLE = "processor_unavailable";
    public static final String AUTHORIZATION_EXPIRED = "authorization_expired";
    public static final String INSUFFICIENT_BALANCE = "insufficient_merchant_balance";
    static final String INSUFFICIENT_FUNDS_REPLY = "INSUFFICIENT_FUNDS";

    private final SagaProperties properties;

    public SagaDecision decide(PaymentSaga saga, CaptureMethod captureMethod, SagaInput input, LocalDateTime now) {
        return switch (saga.getState()) {
            case AUTHORIZING -> authorizing(saga, captureMethod, processor(saga, input), now);
            case AWAITING_CAPTURE -> awaitingCapture(saga, input);
            case CAPTURING -> capturing(saga, processor(saga, input), now);
            case VOIDING -> voiding(saga, processor(saga, input), now);
            case SETTLING -> ledgerStep(saga, input, ledger -> ledger.succeeded()
                    ? SagaDecision.of("SETTLED", null).moveTo(SagaState.COMPLETED, Schedule.NONE)
                            .payment(PaymentChange.SUCCEEDED, null).build()
                    : parked("SETTLEMENT_REJECTED", "Ledger rejected the settlement: " + ledger.reason()));
            case HOLDING -> ledgerStep(saga, input, ledger -> {
                if (ledger.succeeded()) {
                    return SagaDecision.of("HELD", null).moveTo(SagaState.REFUNDING, Schedule.NOW).build();
                }
                if (INSUFFICIENT_FUNDS_REPLY.equals(ledger.reason())) {
                    return SagaDecision.of("INSUFFICIENT_BALANCE", "Merchant balance does not cover the refund")
                            .moveTo(SagaState.REFUND_FAILED, Schedule.NONE)
                            .payment(PaymentChange.REFUND_FAILED, INSUFFICIENT_BALANCE).build();
                }
                return parked("HOLD_REJECTED", "Ledger rejected the hold: " + ledger.reason());
            });
            case REFUNDING -> refunding(saga, processor(saga, input), now);
            case RELEASING -> ledgerStep(saga, input, ledger -> ledger.succeeded()
                    ? SagaDecision.of("RELEASED", null).moveTo(SagaState.REFUND_FAILED, Schedule.NONE)
                            .payment(PaymentChange.REFUND_FAILED, saga.getFailureCode()).build()
                    : parked("RELEASE_REJECTED", "Ledger rejected the hold release: " + ledger.reason()));
            case FINALIZING -> ledgerStep(saga, input, ledger -> ledger.succeeded()
                    ? SagaDecision.of("FINALIZED", null).moveTo(SagaState.REFUNDED, Schedule.NONE)
                            .payment(PaymentChange.REFUNDED, null).build()
                    : parked("FINALIZE_REJECTED", "Ledger rejected the refund posting: " + ledger.reason()));
            default -> throw unexpected(saga, input);
        };
    }

    private SagaDecision authorizing(PaymentSaga saga, CaptureMethod captureMethod, ProcessorResponse response, LocalDateTime now) {
        return switch (response.outcome()) {
            case SUCCEEDED -> captureMethod == CaptureMethod.MANUAL
                    ? SagaDecision.of("AUTHORIZED", response.id()).moveTo(SagaState.AWAITING_CAPTURE, Schedule.AUTHORIZATION_EXPIRY)
                            .payment(PaymentChange.AUTHORIZED, null).build()
                    : SagaDecision.of("AUTHORIZED", response.id()).moveTo(SagaState.CAPTURING, Schedule.NOW)
                            .payment(PaymentChange.AUTHORIZED, null).build();
            // nothing was reserved: no compensation needed
            case DECLINED, REJECTED -> SagaDecision.of(response.outcome().name(), response.describe())
                    .moveTo(SagaState.FAILED, Schedule.NONE)
                    .payment(PaymentChange.FAILED, response.code()).build();
            case UNKNOWN -> stepExpired(saga, now)
                    // the authorization may exist: reverse it (by its request key) before failing
                    ? SagaDecision.of("TIMED_OUT", response.describe()).moveTo(SagaState.VOIDING, Schedule.NOW)
                            .compensate(PaymentStatus.FAILED, PROCESSOR_UNAVAILABLE).build()
                    : retry(response);
        };
    }

    private SagaDecision awaitingCapture(PaymentSaga saga, SagaInput input) {
        return switch (input) {
            case SagaInput.CaptureRequested ignored -> SagaDecision.of("CAPTURE_REQUESTED", null)
                    .moveTo(SagaState.CAPTURING, Schedule.NOW)
                    .payment(PaymentChange.CAPTURE_REQUESTED, null).build();
            case SagaInput.CancelRequested ignored -> SagaDecision.of("CANCEL_REQUESTED", null)
                    .moveTo(SagaState.VOIDING, Schedule.NOW)
                    .compensate(PaymentStatus.CANCELLED, null).build();
            case SagaInput.TimerFired ignored -> SagaDecision.of("AUTHORIZATION_EXPIRED", null)
                    .moveTo(SagaState.VOIDING, Schedule.NOW)
                    .compensate(PaymentStatus.CANCELLED, AUTHORIZATION_EXPIRED).build();
            default -> throw unexpected(saga, input);
        };
    }

    private SagaDecision capturing(PaymentSaga saga, ProcessorResponse response, LocalDateTime now) {
        return switch (response.outcome()) {
            case SUCCEEDED -> SagaDecision.of("CAPTURED", response.id())
                    .moveTo(SagaState.SETTLING, Schedule.REPLY_TIMEOUT)
                    .send(LedgerCommandType.SETTLE_PAYMENT).build();
            // the authorization is still open: release it, then fail the payment
            case DECLINED -> SagaDecision.of("DECLINED", response.describe())
                    .moveTo(SagaState.VOIDING, Schedule.NOW)
                    .compensate(PaymentStatus.FAILED, response.code()).build();
            case REJECTED -> parked("CAPTURE_REJECTED", "Processor rejected the capture: " + response.describe());
            case UNKNOWN -> stepExpired(saga, now)
                    ? parked("CAPTURE_UNKNOWN", "No capture answer within " + properties.stepTimeout(SagaState.CAPTURING)
                    + "; money may have moved, so it is neither voided nor marked failed. Last: " + response.describe())
                    : retry(response);
        };
    }

    private SagaDecision voiding(PaymentSaga saga, ProcessorResponse response, LocalDateTime now) {
        return switch (response.outcome()) {
            case SUCCEEDED -> {
                boolean cancelled = saga.getResolution() == PaymentStatus.CANCELLED;
                yield SagaDecision.of("VOIDED", response.id())
                        .moveTo(cancelled ? SagaState.CANCELLED : SagaState.FAILED, Schedule.NONE)
                        .payment(cancelled ? PaymentChange.CANCELLED : PaymentChange.FAILED, saga.getFailureCode()).build();
            }
            case DECLINED, REJECTED -> parked("VOID_REJECTED", "Processor refused to release the authorization: " + response.describe());
            case UNKNOWN -> stepExpired(saga, now)
                    ? parked("VOID_UNKNOWN", "Authorization not released within " + properties.stepTimeout(SagaState.VOIDING)
                    + ". Last: " + response.describe())
                    : retry(response);
        };
    }

    private SagaDecision refunding(PaymentSaga saga, ProcessorResponse response, LocalDateTime now) {
        return switch (response.outcome()) {
            case SUCCEEDED -> SagaDecision.of("REFUNDED_AT_PROCESSOR", response.id())
                    .moveTo(SagaState.FINALIZING, Schedule.REPLY_TIMEOUT)
                    .send(LedgerCommandType.FINALIZE_REFUND).build();
            // compensation: give the held amount back to the merchant's balance
            case DECLINED, REJECTED -> SagaDecision.of(response.outcome().name(), response.describe())
                    .moveTo(SagaState.RELEASING, Schedule.REPLY_TIMEOUT)
                    .send(LedgerCommandType.RELEASE_HOLD)
                    .code(response.code() != null ? response.code() : "refund_rejected").build();
            case UNKNOWN -> stepExpired(saga, now)
                    ? parked("REFUND_UNKNOWN", "No refund answer within " + properties.stepTimeout(SagaState.REFUNDING)
                    + "; the hold stays in place. Last: " + response.describe())
                    : retry(response);
        };
    }

    private record LedgerResult(boolean succeeded, String reason) {
    }

    /** A ledger step: decide on the reply, or re-send the command if the reply is overdue. */
    private SagaDecision ledgerStep(PaymentSaga saga, SagaInput input,
                                    java.util.function.Function<LedgerResult, SagaDecision> onReply) {
        return switch (input) {
            case SagaInput.LedgerAnswer answer -> onReply.apply(
                    new LedgerResult("SUCCEEDED".equals(answer.outcome()), answer.reason()));
            case SagaInput.TimerFired ignored -> SagaDecision.of("REPLY_OVERDUE", "Re-sending command " + saga.getPendingCommandId())
                    .resend().build();
            default -> throw unexpected(saga, input);
        };
    }

    private static SagaDecision retry(ProcessorResponse response) {
        return SagaDecision.of("UNKNOWN", response.describe()).stay(Schedule.BACKOFF).build();
    }

    private static SagaDecision parked(String outcome, String reason) {
        return SagaDecision.of(outcome, reason).park(reason).build();
    }

    private boolean stepExpired(PaymentSaga saga, LocalDateTime now) {
        return !now.isBefore(saga.getStepStartedAt().plus(properties.stepTimeout(saga.getState())));
    }

    private static ProcessorResponse processor(PaymentSaga saga, SagaInput input) {
        if (input instanceof SagaInput.ProcessorAnswer answer) {
            return answer.response();
        }
        throw unexpected(saga, input);
    }

    private static IllegalStateException unexpected(PaymentSaga saga, SagaInput input) {
        return new IllegalStateException("Saga " + saga.getId() + " in " + saga.getState()
                + " can't handle " + input.getClass().getSimpleName());
    }
}
