package com.shylesh.payout_service.saga;

import com.shylesh.payout_service.client.BankResponse;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Every transition of the payout saga: the pivot, compensation, polling, timeouts and returns. */
class PayoutSagaStateMachineTest {

    private static final LocalDateTime START = LocalDateTime.of(2026, 10, 9, 12, 0);

    private final SagaProperties properties = new SagaProperties(Duration.ofMillis(500), 4, 100, Duration.ofSeconds(30),
            Duration.ofMinutes(10), Duration.ofDays(3), Duration.ofHours(1), Duration.ofDays(5), Duration.ofHours(6),
            new SagaProperties.Retry(Duration.ofSeconds(1), Duration.ofSeconds(30)),
            new SagaProperties.Replies(Duration.ofSeconds(15), Duration.ofMinutes(5)));

    private final PayoutSagaStateMachine machine = new PayoutSagaStateMachine(properties);

    private static PayoutSaga saga(SagaState state) {
        return PayoutSaga.builder().id(UUID.randomUUID()).payoutId(UUID.randomUUID()).state(state)
                .stepStartedAt(START).createdAt(START).updatedAt(START).pendingCommandId(UUID.randomUUID()).build();
    }

    private static SagaInput ledger(String outcome, String reason) {
        return new SagaInput.LedgerAnswer(outcome, reason);
    }

    private static SagaInput bank(String transferStatus) {
        return new SagaInput.BankAnswer(new BankResponse(BankResponse.Outcome.OK, 200, "tr_1", transferStatus,
                "FAILED".equals(transferStatus) ? "account_closed" : "RETURNED".equals(transferStatus) ? "account_frozen" : null, null));
    }

    private static SagaInput bank(BankResponse.Outcome outcome, String code) {
        return new SagaInput.BankAnswer(new BankResponse(outcome, outcome == BankResponse.Outcome.DECLINED ? 402 : 503,
                null, null, code, "test"));
    }

    // ---------------------------------------------------------------- holding

    @Test
    void aHeldPayoutIsSentToTheBank() {
        SagaDecision decision = machine.decide(saga(SagaState.HOLDING), ledger("SUCCEEDED", null), START);

        assertThat(decision.moveTo()).isEqualTo(SagaState.SUBMITTING);
        assertThat(decision.schedule()).isEqualTo(Schedule.NOW);
    }

    @Test
    void aHoldThePayableBalanceDoesNotCoverFailsThePayoutWithNothingToUndo() {
        SagaDecision decision = machine.decide(saga(SagaState.HOLDING), ledger("REJECTED", "INSUFFICIENT_FUNDS"), START);

        assertThat(decision.moveTo()).isEqualTo(SagaState.FAILED);
        assertThat(decision.payoutChange()).isEqualTo(PayoutChange.FAILED);
        assertThat(decision.code()).isEqualTo(PayoutSagaStateMachine.INSUFFICIENT_PAYABLE_BALANCE);
        assertThat(decision.sendCommand()).isNull();
    }

    @Test
    void anyOtherLedgerRejectionParksTheSaga() {
        assertThat(machine.decide(saga(SagaState.HOLDING), ledger("REJECTED", "SOMETHING"), START).parkReason()).contains("SOMETHING");
        assertThat(machine.decide(saga(SagaState.FINALIZING), ledger("REJECTED", "NO_ACTIVE_HOLD"), START).parkReason()).isNotNull();
        assertThat(machine.decide(saga(SagaState.RELEASING), ledger("REJECTED", "NO_ACTIVE_HOLD"), START).parkReason()).isNotNull();
        assertThat(machine.decide(saga(SagaState.RETURNING), ledger("REJECTED", "NOT_PAID_OUT"), START).parkReason()).isNotNull();
    }

    @Test
    void anOverdueLedgerReplyResendsTheSameCommand() {
        SagaDecision decision = machine.decide(saga(SagaState.FINALIZING), new SagaInput.TimerFired(), START);

        assertThat(decision.resendCommand()).isTrue();
        assertThat(decision.moveTo()).isNull();
        assertThat(decision.schedule()).isEqualTo(Schedule.REPLY_TIMEOUT);
    }

    // ---------------------------------------------------------------- submitting: the pivot

    @Test
    void anAcceptedTransferGoesInTransit() {
        SagaDecision decision = machine.decide(saga(SagaState.SUBMITTING), bank("PENDING"), START);

        assertThat(decision.moveTo()).isEqualTo(SagaState.IN_TRANSIT);
        assertThat(decision.schedule()).isEqualTo(Schedule.TRANSIT_POLL);
        assertThat(decision.payoutChange()).isEqualTo(PayoutChange.IN_TRANSIT);
    }

    @Test
    void aDeclinedTransferReleasesTheHold() {
        SagaDecision decision = machine.decide(saga(SagaState.SUBMITTING), bank(BankResponse.Outcome.DECLINED, "invalid_account"), START);

        assertThat(decision.moveTo()).isEqualTo(SagaState.RELEASING);
        assertThat(decision.sendCommand()).isEqualTo(LedgerCommandType.RELEASE_PAYOUT);
        assertThat(decision.code()).isEqualTo("invalid_account");
        assertThat(decision.payoutChange()).isEqualTo(PayoutChange.NONE);
    }

    @Test
    void anUnknownTransferOutcomeIsRetriedThenParkedButNeverReleased() {
        SagaDecision early = machine.decide(saga(SagaState.SUBMITTING), bank(BankResponse.Outcome.UNKNOWN, "unreachable"), START.plusMinutes(9));
        SagaDecision late = machine.decide(saga(SagaState.SUBMITTING), bank(BankResponse.Outcome.UNKNOWN, "unreachable"), START.plusMinutes(10));

        assertThat(early.schedule()).isEqualTo(Schedule.BACKOFF);
        assertThat(early.moveTo()).isNull();
        assertThat(late.parkReason()).contains("may have been sent");
        assertThat(late.sendCommand()).isNull();
    }

    @Test
    void aRejectedTransferRequestParksTheSaga() {
        assertThat(machine.decide(saga(SagaState.SUBMITTING), bank(BankResponse.Outcome.REJECTED, "idempotency_key_reused"), START)
                .parkReason()).isNotNull();
    }

    @Test
    void aTransferThatAnswersLateAsPaidIsFinalizedRightAway() {
        SagaDecision decision = machine.decide(saga(SagaState.SUBMITTING), bank("PAID"), START);

        assertThat(decision.moveTo()).isEqualTo(SagaState.FINALIZING);
        assertThat(decision.sendCommand()).isEqualTo(LedgerCommandType.FINALIZE_PAYOUT);
        assertThat(decision.payoutChange()).isEqualTo(PayoutChange.IN_TRANSIT);
    }

    // ---------------------------------------------------------------- in transit

    @Test
    void aPendingTransferIsPolledUntilTheInTransitTimeoutThenParked() {
        SagaDecision polled = machine.decide(saga(SagaState.IN_TRANSIT), bank("PENDING"), START.plusDays(2));
        SagaDecision tooLong = machine.decide(saga(SagaState.IN_TRANSIT), bank("PENDING"), START.plusDays(3));

        assertThat(polled.schedule()).isEqualTo(Schedule.TRANSIT_POLL);
        assertThat(polled.moveTo()).isNull();
        assertThat(tooLong.parkReason()).contains("still in transit");
    }

    @Test
    void aPaidTransferIsFinalizedInTheLedger() {
        SagaDecision decision = machine.decide(saga(SagaState.IN_TRANSIT), bank("PAID"), START);

        assertThat(decision.moveTo()).isEqualTo(SagaState.FINALIZING);
        assertThat(decision.sendCommand()).isEqualTo(LedgerCommandType.FINALIZE_PAYOUT);
    }

    @Test
    void aTransferTheBankFailsReleasesTheHoldWithTheBanksCode() {
        SagaDecision decision = machine.decide(saga(SagaState.IN_TRANSIT), bank("FAILED"), START);

        assertThat(decision.moveTo()).isEqualTo(SagaState.RELEASING);
        assertThat(decision.sendCommand()).isEqualTo(LedgerCommandType.RELEASE_PAYOUT);
        assertThat(decision.code()).isEqualTo("account_closed");
    }

    @Test
    void anUnreachableBankDuringTransitIsRetried() {
        assertThat(machine.decide(saga(SagaState.IN_TRANSIT), bank(BankResponse.Outcome.UNKNOWN, "unreachable"), START).schedule())
                .isEqualTo(Schedule.BACKOFF);
    }

    // ---------------------------------------------------------------- finalizing, releasing

    @Test
    void aFinalizedPayoutIsPaidAndWatchedForReturns() {
        SagaDecision decision = machine.decide(saga(SagaState.FINALIZING), ledger("SUCCEEDED", null), START);

        assertThat(decision.moveTo()).isEqualTo(SagaState.RETURN_WINDOW);
        assertThat(decision.schedule()).isEqualTo(Schedule.RETURN_CHECK);
        assertThat(decision.payoutChange()).isEqualTo(PayoutChange.PAID);
    }

    @Test
    void aReleasedHoldFailsThePayoutWithTheRememberedCode() {
        PayoutSaga saga = saga(SagaState.RELEASING);
        saga.recordFailureCode("invalid_account");

        SagaDecision decision = machine.decide(saga, ledger("SUCCEEDED", null), START);

        assertThat(decision.moveTo()).isEqualTo(SagaState.FAILED);
        assertThat(decision.payoutChange()).isEqualTo(PayoutChange.FAILED);
        assertThat(decision.code()).isEqualTo("invalid_account");
    }

    // ---------------------------------------------------------------- return window

    @Test
    void aPaidPayoutIsCheckedUntilTheWindowClosesThenCompleted() {
        SagaDecision during = machine.decide(saga(SagaState.RETURN_WINDOW), bank("PAID"), START.plusDays(4));
        SagaDecision after = machine.decide(saga(SagaState.RETURN_WINDOW), bank("PAID"), START.plusDays(5));

        assertThat(during.schedule()).isEqualTo(Schedule.RETURN_CHECK);
        assertThat(during.moveTo()).isNull();
        assertThat(after.moveTo()).isEqualTo(SagaState.COMPLETED);
        assertThat(after.payoutChange()).isEqualTo(PayoutChange.NONE);
    }

    @Test
    void aReturnedTransferIsBookedBackToTheMerchant() {
        SagaDecision decision = machine.decide(saga(SagaState.RETURN_WINDOW), bank("RETURNED"), START);

        assertThat(decision.moveTo()).isEqualTo(SagaState.RETURNING);
        assertThat(decision.sendCommand()).isEqualTo(LedgerCommandType.RETURN_PAYOUT);
        assertThat(decision.code()).isEqualTo("account_frozen");
    }

    @Test
    void aBookedReturnMarksThePayoutReturned() {
        PayoutSaga saga = saga(SagaState.RETURNING);
        saga.recordFailureCode("account_frozen");

        SagaDecision decision = machine.decide(saga, ledger("SUCCEEDED", null), START);

        assertThat(decision.moveTo()).isEqualTo(SagaState.RETURNED);
        assertThat(decision.payoutChange()).isEqualTo(PayoutChange.RETURNED);
        assertThat(decision.code()).isEqualTo("account_frozen");
    }

    @Test
    void anUnreachableBankDuringTheReturnWindowIsRetriedNotParked() {
        SagaDecision decision = machine.decide(saga(SagaState.RETURN_WINDOW), bank(BankResponse.Outcome.UNKNOWN, "unreachable"), START.plusDays(6));

        assertThat(decision.schedule()).isEqualTo(Schedule.BACKOFF);
        assertThat(decision.parkReason()).isNull();
    }

    @Test
    void aPaidTransferThatTurnsPendingOrFailedIsNeverGuessedAt() {
        assertThat(machine.decide(saga(SagaState.RETURN_WINDOW), bank("FAILED"), START).parkReason()).isNotNull();
        assertThat(machine.decide(saga(SagaState.RETURN_WINDOW), bank("PENDING"), START).parkReason()).isNotNull();
    }

    @Test
    void anInputAStepCantHandleIsABug() {
        assertThatThrownBy(() -> machine.decide(saga(SagaState.SUBMITTING), ledger("SUCCEEDED", null), START))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> machine.decide(saga(SagaState.HOLDING), bank("PAID"), START))
                .isInstanceOf(IllegalStateException.class);
    }
}
