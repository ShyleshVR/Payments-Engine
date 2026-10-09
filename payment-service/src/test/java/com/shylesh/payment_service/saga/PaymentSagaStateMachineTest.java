package com.shylesh.payment_service.saga;

import com.shylesh.payment_service.entity.CaptureMethod;
import com.shylesh.payment_service.entity.PaymentStatus;
import com.shylesh.payment_service.processor.ProcessorResponse;
import com.shylesh.payment_service.processor.ProcessorResponse.Outcome;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Every transition of the payment and refund sagas, including compensation and timeouts. */
class PaymentSagaStateMachineTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 8, 12, 0);

    private final SagaProperties properties = new SagaProperties(
            Duration.ofMillis(500), 8, 100, Duration.ofSeconds(30), Duration.ofDays(7),
            new SagaProperties.StepTimeouts(Duration.ofMinutes(2), Duration.ofMinutes(10), Duration.ofHours(24), Duration.ofMinutes(10)),
            new SagaProperties.Retry(Duration.ofSeconds(1), Duration.ofSeconds(30)),
            new SagaProperties.Replies(Duration.ofSeconds(15), Duration.ofMinutes(5)));

    private final PaymentSagaStateMachine machine = new PaymentSagaStateMachine(properties);

    /** A saga in the given state whose current step started stepAge ago. */
    private static PaymentSaga saga(SagaState state, Duration stepAge) {
        PaymentSaga saga = PaymentSaga.start(UUID.randomUUID(), state.type() == null ? SagaType.PAYMENT : state.type(),
                state, null, NOW.minus(stepAge));
        saga.moveTo(state, NOW.minus(stepAge));
        return saga;
    }

    private static PaymentSaga saga(SagaState state) {
        return saga(state, Duration.ZERO);
    }

    private static SagaInput processor(Outcome outcome, String code) {
        return new SagaInput.ProcessorAnswer(new ProcessorResponse(outcome, outcome == Outcome.SUCCEEDED ? 200 : 402,
                code, outcome == Outcome.SUCCEEDED ? "auth_123" : null, null));
    }

    private static SagaInput unknown() {
        return new SagaInput.ProcessorAnswer(ProcessorResponse.unknown("unreachable", "connect timed out"));
    }

    private static SagaInput ledger(String outcome, String reason) {
        return new SagaInput.LedgerAnswer(outcome, reason);
    }

    private SagaDecision decide(PaymentSaga saga, SagaInput input) {
        return machine.decide(saga, CaptureMethod.AUTOMATIC, input, NOW);
    }

    @Nested
    class Authorizing {

        @Test
        void approvedWithAutomaticCaptureGoesStraightToCapture() {
            SagaDecision decision = decide(saga(SagaState.AUTHORIZING), processor(Outcome.SUCCEEDED, null));

            assertThat(decision.moveTo()).isEqualTo(SagaState.CAPTURING);
            assertThat(decision.schedule()).isEqualTo(Schedule.NOW);
            assertThat(decision.paymentChange()).isEqualTo(PaymentChange.AUTHORIZED);
        }

        @Test
        void approvedWithManualCaptureWaitsForTheMerchantUntilExpiry() {
            SagaDecision decision = machine.decide(saga(SagaState.AUTHORIZING), CaptureMethod.MANUAL,
                    processor(Outcome.SUCCEEDED, null), NOW);

            assertThat(decision.moveTo()).isEqualTo(SagaState.AWAITING_CAPTURE);
            assertThat(decision.schedule()).isEqualTo(Schedule.AUTHORIZATION_EXPIRY);
        }

        @ParameterizedTest
        @EnumSource(value = Outcome.class, names = {"DECLINED", "REJECTED"})
        void refusedAuthorizationFailsThePaymentWithoutCompensation(Outcome outcome) {
            SagaDecision decision = decide(saga(SagaState.AUTHORIZING), processor(outcome, "do_not_honor"));

            assertThat(decision.moveTo()).isEqualTo(SagaState.FAILED);
            assertThat(decision.paymentChange()).isEqualTo(PaymentChange.FAILED);
            assertThat(decision.code()).isEqualTo("do_not_honor");
            assertThat(decision.compensateTo()).isNull();
        }

        @Test
        void unknownOutcomeIsRetriedWhileTheStepHasTime() {
            SagaDecision decision = decide(saga(SagaState.AUTHORIZING, Duration.ofSeconds(90)), unknown());

            assertThat(decision.moveTo()).isNull();
            assertThat(decision.schedule()).isEqualTo(Schedule.BACKOFF);
        }

        @Test
        void unknownOutcomePastTheDeadlineReversesTheAuthorizationAndFailsThePayment() {
            SagaDecision decision = decide(saga(SagaState.AUTHORIZING, Duration.ofMinutes(2)), unknown());

            assertThat(decision.moveTo()).isEqualTo(SagaState.VOIDING);
            assertThat(decision.compensateTo()).isEqualTo(PaymentStatus.FAILED);
            assertThat(decision.code()).isEqualTo(PaymentSagaStateMachine.PROCESSOR_UNAVAILABLE);
            assertThat(decision.paymentChange()).isEqualTo(PaymentChange.NONE);
        }
    }

    @Nested
    class AwaitingCapture {

        @Test
        void captureRequestCapturesNow() {
            SagaDecision decision = decide(saga(SagaState.AWAITING_CAPTURE), new SagaInput.CaptureRequested());

            assertThat(decision.moveTo()).isEqualTo(SagaState.CAPTURING);
            assertThat(decision.paymentChange()).isEqualTo(PaymentChange.CAPTURE_REQUESTED);
        }

        @Test
        void cancelVoidsAndEndsCancelled() {
            SagaDecision decision = decide(saga(SagaState.AWAITING_CAPTURE), new SagaInput.CancelRequested());

            assertThat(decision.moveTo()).isEqualTo(SagaState.VOIDING);
            assertThat(decision.compensateTo()).isEqualTo(PaymentStatus.CANCELLED);
            assertThat(decision.code()).isNull();
        }

        @Test
        void expiryVoidsWithAReason() {
            SagaDecision decision = decide(saga(SagaState.AWAITING_CAPTURE), new SagaInput.TimerFired());

            assertThat(decision.compensateTo()).isEqualTo(PaymentStatus.CANCELLED);
            assertThat(decision.code()).isEqualTo(PaymentSagaStateMachine.AUTHORIZATION_EXPIRED);
        }

        @Test
        void aProcessorAnswerIsNotExpectedHere() {
            assertThatThrownBy(() -> decide(saga(SagaState.AWAITING_CAPTURE), processor(Outcome.SUCCEEDED, null)))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    class Capturing {

        @Test
        void capturedPaymentIsSettledInTheLedger() {
            SagaDecision decision = decide(saga(SagaState.CAPTURING), processor(Outcome.SUCCEEDED, null));

            assertThat(decision.moveTo()).isEqualTo(SagaState.SETTLING);
            assertThat(decision.sendCommand()).isEqualTo(LedgerCommandType.SETTLE_PAYMENT);
        }

        @Test
        void declinedCaptureReleasesTheAuthorizationThenFails() {
            SagaDecision decision = decide(saga(SagaState.CAPTURING), processor(Outcome.DECLINED, "capture_declined"));

            assertThat(decision.moveTo()).isEqualTo(SagaState.VOIDING);
            assertThat(decision.compensateTo()).isEqualTo(PaymentStatus.FAILED);
            assertThat(decision.code()).isEqualTo("capture_declined");
        }

        @Test
        void rejectedCaptureIsLeftToAnOperator() {
            SagaDecision decision = decide(saga(SagaState.CAPTURING), processor(Outcome.REJECTED, "already_captured"));

            assertThat(decision.parkReason()).contains("already_captured");
        }

        /** The pivot: once a capture may have happened, the saga never voids or fails on its own. */
        @Test
        void unknownCaptureOutcomeIsNeverCompensatedEvenPastTheDeadline() {
            SagaDecision retrying = decide(saga(SagaState.CAPTURING, Duration.ofMinutes(9)), unknown());
            SagaDecision expired = decide(saga(SagaState.CAPTURING, Duration.ofHours(5)), unknown());

            assertThat(retrying.schedule()).isEqualTo(Schedule.BACKOFF);
            assertThat(expired.parkReason()).contains("money may have moved");
            assertThat(expired.moveTo()).isNull();
            assertThat(expired.compensateTo()).isNull();
            assertThat(expired.paymentChange()).isEqualTo(PaymentChange.NONE);
        }
    }

    @Nested
    class Voiding {

        @Test
        void releasedAuthorizationEndsAsTheCompensationIntended() {
            PaymentSaga cancelling = saga(SagaState.VOIDING);
            cancelling.compensate(PaymentStatus.CANCELLED, PaymentSagaStateMachine.AUTHORIZATION_EXPIRED);
            PaymentSaga failing = saga(SagaState.VOIDING);
            failing.compensate(PaymentStatus.FAILED, "capture_declined");

            SagaDecision cancelled = decide(cancelling, processor(Outcome.SUCCEEDED, null));
            SagaDecision failed = decide(failing, processor(Outcome.SUCCEEDED, null));

            assertThat(cancelled.moveTo()).isEqualTo(SagaState.CANCELLED);
            assertThat(cancelled.paymentChange()).isEqualTo(PaymentChange.CANCELLED);
            assertThat(cancelled.code()).isEqualTo(PaymentSagaStateMachine.AUTHORIZATION_EXPIRED);
            assertThat(failed.moveTo()).isEqualTo(SagaState.FAILED);
            assertThat(failed.code()).isEqualTo("capture_declined");
        }

        @Test
        void refusalOrLastingSilenceIsLeftToAnOperator() {
            assertThat(decide(saga(SagaState.VOIDING), processor(Outcome.REJECTED, "already_captured")).parkReason()).isNotNull();
            assertThat(decide(saga(SagaState.VOIDING, Duration.ofHours(1)), unknown()).schedule()).isEqualTo(Schedule.BACKOFF);
            assertThat(decide(saga(SagaState.VOIDING, Duration.ofHours(24)), unknown()).parkReason()).isNotNull();
        }
    }

    @Nested
    class Settling {

        @Test
        void settlementCompletesThePayment() {
            SagaDecision decision = decide(saga(SagaState.SETTLING), ledger("SUCCEEDED", null));

            assertThat(decision.moveTo()).isEqualTo(SagaState.COMPLETED);
            assertThat(decision.paymentChange()).isEqualTo(PaymentChange.SUCCEEDED);
        }

        @Test
        void overdueReplyResendsTheSameCommand() {
            SagaDecision decision = decide(saga(SagaState.SETTLING), new SagaInput.TimerFired());

            assertThat(decision.resendCommand()).isTrue();
            assertThat(decision.sendCommand()).isNull();
            assertThat(decision.moveTo()).isNull();
        }

        @Test
        void rejectedSettlementIsLeftToAnOperator() {
            assertThat(decide(saga(SagaState.SETTLING), ledger("REJECTED", "WHATEVER")).parkReason()).isNotNull();
        }
    }

    @Nested
    class Refund {

        @Test
        void heldAmountIsRefundedAtTheProcessor() {
            SagaDecision decision = decide(saga(SagaState.HOLDING), ledger("SUCCEEDED", null));

            assertThat(decision.moveTo()).isEqualTo(SagaState.REFUNDING);
            assertThat(decision.schedule()).isEqualTo(Schedule.NOW);
        }

        @Test
        void insufficientMerchantBalanceFailsTheRefundWithoutCompensation() {
            SagaDecision decision = decide(saga(SagaState.HOLDING), ledger("REJECTED", "INSUFFICIENT_FUNDS"));

            assertThat(decision.moveTo()).isEqualTo(SagaState.REFUND_FAILED);
            assertThat(decision.paymentChange()).isEqualTo(PaymentChange.REFUND_FAILED);
            assertThat(decision.code()).isEqualTo(PaymentSagaStateMachine.INSUFFICIENT_BALANCE);
            assertThat(decision.sendCommand()).isNull();
        }

        @Test
        void otherHoldRejectionsAreLeftToAnOperator() {
            assertThat(decide(saga(SagaState.HOLDING), ledger("REJECTED", "NO_ACTIVE_HOLD")).parkReason()).isNotNull();
        }

        @Test
        void processorRefundFinalizesTheHold() {
            SagaDecision decision = decide(saga(SagaState.REFUNDING), processor(Outcome.SUCCEEDED, null));

            assertThat(decision.moveTo()).isEqualTo(SagaState.FINALIZING);
            assertThat(decision.sendCommand()).isEqualTo(LedgerCommandType.FINALIZE_REFUND);
        }

        @ParameterizedTest
        @EnumSource(value = Outcome.class, names = {"DECLINED", "REJECTED"})
        void refusedRefundReleasesTheHold(Outcome outcome) {
            SagaDecision decision = decide(saga(SagaState.REFUNDING), processor(outcome, "refund_declined"));

            assertThat(decision.moveTo()).isEqualTo(SagaState.RELEASING);
            assertThat(decision.sendCommand()).isEqualTo(LedgerCommandType.RELEASE_HOLD);
            assertThat(decision.code()).isEqualTo("refund_declined");
            assertThat(decision.paymentChange()).isEqualTo(PaymentChange.NONE);
        }

        @Test
        void unknownRefundOutcomeKeepsTheHoldAndIsLeftToAnOperatorEventually() {
            assertThat(decide(saga(SagaState.REFUNDING, Duration.ofMinutes(1)), unknown()).schedule()).isEqualTo(Schedule.BACKOFF);
            SagaDecision expired = decide(saga(SagaState.REFUNDING, Duration.ofMinutes(10)), unknown());
            assertThat(expired.parkReason()).contains("hold stays");
            assertThat(expired.sendCommand()).isNull();
        }

        @Test
        void releasedHoldFailsTheRefundWithTheProcessorsReason() {
            PaymentSaga releasing = saga(SagaState.RELEASING);
            releasing.recordFailureCode("refund_declined");

            SagaDecision decision = decide(releasing, ledger("SUCCEEDED", null));

            assertThat(decision.moveTo()).isEqualTo(SagaState.REFUND_FAILED);
            assertThat(decision.paymentChange()).isEqualTo(PaymentChange.REFUND_FAILED);
            assertThat(decision.code()).isEqualTo("refund_declined");
        }

        @Test
        void finalizedRefundRefundsThePayment() {
            SagaDecision decision = decide(saga(SagaState.FINALIZING), ledger("SUCCEEDED", null));

            assertThat(decision.moveTo()).isEqualTo(SagaState.REFUNDED);
            assertThat(decision.paymentChange()).isEqualTo(PaymentChange.REFUNDED);
        }

        @Test
        void overdueLedgerRepliesAreResentInEveryLedgerStep() {
            for (SagaState state : new SagaState[]{SagaState.HOLDING, SagaState.RELEASING, SagaState.FINALIZING}) {
                assertThat(decide(saga(state), new SagaInput.TimerFired()).resendCommand()).as(state.name()).isTrue();
            }
        }
    }
}
