package com.shylesh.payout_service.saga;

import com.shylesh.payout_service.client.BankResponse;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * The payout saga as a pure function: (current state, what happened) -> decision. No I/O, so every
 * transition, compensation and timeout rule is unit-tested directly.
 *
 * HOLDING -> SUBMITTING -> IN_TRANSIT -> FINALIZING -> RETURN_WINDOW -> COMPLETED
 *   - a hold the payable balance doesn't cover fails the payout (nothing to undo);
 *   - the bank declining the transfer, or failing it later, is compensated by RELEASING the hold;
 *   - a transfer request whose outcome stays unknown, or one stuck in transit, parks the saga for
 *     an operator: the money may have moved, so the hold is never released blindly;
 *   - a paid transfer the merchant's bank sends back is booked by RETURNING (-> RETURNED).
 */
@Component
@RequiredArgsConstructor
public class PayoutSagaStateMachine {

    public static final String INSUFFICIENT_PAYABLE_BALANCE = "insufficient_payable_balance";
    public static final String TRANSFER_DECLINED = "transfer_declined";
    public static final String TRANSFER_FAILED = "transfer_failed";
    public static final String TRANSFER_RETURNED = "transfer_returned";
    static final String INSUFFICIENT_FUNDS_REPLY = "INSUFFICIENT_FUNDS";
    static final String SUCCEEDED = "SUCCEEDED";

    private final SagaProperties properties;

    public SagaDecision decide(PayoutSaga saga, SagaInput input, LocalDateTime now) {
        return switch (saga.getState()) {
            case HOLDING -> ledgerStep(saga, input, (succeeded, reason) -> {
                if (succeeded) {
                    return SagaDecision.of("HELD", null).moveTo(SagaState.SUBMITTING, Schedule.NOW).build();
                }
                if (INSUFFICIENT_FUNDS_REPLY.equals(reason)) {
                    return SagaDecision.of("INSUFFICIENT_BALANCE", "The payable balance doesn't cover the payout")
                            .moveTo(SagaState.FAILED, Schedule.NONE)
                            .payout(PayoutChange.FAILED).code(INSUFFICIENT_PAYABLE_BALANCE).build();
                }
                return parked("HOLD_REJECTED", "Ledger rejected the hold: " + reason);
            });
            case SUBMITTING -> submitting(saga, bank(saga, input), now);
            case IN_TRANSIT -> inTransit(saga, bank(saga, input), now);
            case FINALIZING -> ledgerStep(saga, input, (succeeded, reason) -> succeeded
                    ? SagaDecision.of("FINALIZED", null).moveTo(SagaState.RETURN_WINDOW, Schedule.RETURN_CHECK)
                            .payout(PayoutChange.PAID).build()
                    : parked("FINALIZE_REJECTED", "Ledger rejected the payout posting: " + reason));
            case RELEASING -> ledgerStep(saga, input, (succeeded, reason) -> succeeded
                    ? SagaDecision.of("RELEASED", null).moveTo(SagaState.FAILED, Schedule.NONE)
                            .payout(PayoutChange.FAILED).code(saga.getFailureCode()).build()
                    : parked("RELEASE_REJECTED", "Ledger rejected the hold release: " + reason));
            case RETURN_WINDOW -> returnWindow(saga, bank(saga, input), now);
            case RETURNING -> ledgerStep(saga, input, (succeeded, reason) -> succeeded
                    ? SagaDecision.of("RETURN_BOOKED", null).moveTo(SagaState.RETURNED, Schedule.NONE)
                            .payout(PayoutChange.RETURNED).code(saga.getFailureCode()).build()
                    : parked("RETURN_REJECTED", "Ledger rejected the return posting: " + reason));
            default -> throw unexpected(saga, input);
        };
    }

    /** The pivot: until the bank has the transfer, a failure is compensated; after, only forward. */
    private SagaDecision submitting(PayoutSaga saga, BankResponse response, LocalDateTime now) {
        return switch (response.outcome()) {
            case OK -> accepted(response);
            // nothing was sent: give the held amount back
            case DECLINED -> SagaDecision.of("DECLINED", response.describe())
                    .moveTo(SagaState.RELEASING, Schedule.REPLY_TIMEOUT)
                    .send(LedgerCommandType.RELEASE_PAYOUT)
                    .code(response.code() != null ? response.code() : TRANSFER_DECLINED).build();
            case REJECTED -> parked("TRANSFER_REJECTED", "Bank rejected the transfer request: " + response.describe());
            case UNKNOWN -> expired(saga, now, properties.submitTimeout())
                    ? parked("TRANSFER_UNKNOWN", "No answer to the transfer within " + properties.submitTimeout()
                    + "; it may have been sent, so the hold is kept. Last: " + response.describe())
                    : retry(response);
        };
    }

    private SagaDecision accepted(BankResponse response) {
        return switch (response.transferStatus()) {
            case "PENDING" -> SagaDecision.of("ACCEPTED", response.transferId())
                    .moveTo(SagaState.IN_TRANSIT, Schedule.TRANSIT_POLL)
                    .payout(PayoutChange.IN_TRANSIT).build();
            // answered late (e.g. a retry after a lost answer): the bank has moved on already
            case "PAID", "RETURNED" -> SagaDecision.of("PAID_BY_BANK", response.transferId())
                    .moveTo(SagaState.FINALIZING, Schedule.REPLY_TIMEOUT)
                    .send(LedgerCommandType.FINALIZE_PAYOUT)
                    .payout(PayoutChange.IN_TRANSIT).build();
            case "FAILED" -> SagaDecision.of("FAILED_BY_BANK", response.describe())
                    .moveTo(SagaState.RELEASING, Schedule.REPLY_TIMEOUT)
                    .send(LedgerCommandType.RELEASE_PAYOUT)
                    .payout(PayoutChange.IN_TRANSIT)
                    .code(response.code() != null ? response.code() : TRANSFER_FAILED).build();
            default -> parked("UNEXPECTED_TRANSFER_STATE", "Bank reported " + response.describe());
        };
    }

    private SagaDecision inTransit(PayoutSaga saga, BankResponse response, LocalDateTime now) {
        if (response.outcome() == BankResponse.Outcome.UNKNOWN) {
            return expired(saga, now, properties.inTransitTimeout())
                    ? parked("TRANSFER_STATUS_UNKNOWN", "No answer about the transfer within " + properties.inTransitTimeout()
                    + ". Last: " + response.describe())
                    : retry(response);
        }
        if (response.outcome() != BankResponse.Outcome.OK) {
            return parked("TRANSFER_STATUS_REJECTED", "Bank refused the status request: " + response.describe());
        }
        return switch (response.transferStatus()) {
            case "PENDING" -> expired(saga, now, properties.inTransitTimeout())
                    ? parked("IN_TRANSIT_TOO_LONG", "Transfer " + response.transferId() + " still in transit after "
                    + properties.inTransitTimeout())
                    : SagaDecision.of("STILL_IN_TRANSIT", null).stay(Schedule.TRANSIT_POLL).build();
            case "PAID", "RETURNED" -> SagaDecision.of("PAID_BY_BANK", response.transferId())
                    .moveTo(SagaState.FINALIZING, Schedule.REPLY_TIMEOUT)
                    .send(LedgerCommandType.FINALIZE_PAYOUT).build();
            // the bank gave up on it: no money moved, give the held amount back
            case "FAILED" -> SagaDecision.of("FAILED_BY_BANK", response.describe())
                    .moveTo(SagaState.RELEASING, Schedule.REPLY_TIMEOUT)
                    .send(LedgerCommandType.RELEASE_PAYOUT)
                    .code(response.code() != null ? response.code() : TRANSFER_FAILED).build();
            default -> parked("UNEXPECTED_TRANSFER_STATE", "Bank reported " + response.describe());
        };
    }

    /** Paid money can still come back; it is watched until the return window closes. */
    private SagaDecision returnWindow(PayoutSaga saga, BankResponse response, LocalDateTime now) {
        if (response.outcome() == BankResponse.Outcome.UNKNOWN) {
            return retry(response);
        }
        if (response.outcome() != BankResponse.Outcome.OK) {
            return parked("TRANSFER_STATUS_REJECTED", "Bank refused the status request: " + response.describe());
        }
        return switch (response.transferStatus()) {
            case "PAID" -> expired(saga, now, properties.returnWindow())
                    ? SagaDecision.of("RETURN_WINDOW_CLOSED", null).moveTo(SagaState.COMPLETED, Schedule.NONE).build()
                    : SagaDecision.of("STILL_PAID", null).stay(Schedule.RETURN_CHECK).build();
            case "RETURNED" -> SagaDecision.of("RETURNED_BY_BANK", response.describe())
                    .moveTo(SagaState.RETURNING, Schedule.REPLY_TIMEOUT)
                    .send(LedgerCommandType.RETURN_PAYOUT)
                    .code(response.code() != null ? response.code() : TRANSFER_RETURNED).build();
            default -> parked("UNEXPECTED_TRANSFER_STATE", "A paid transfer is now " + response.describe());
        };
    }

    /** What a ledger step decides on the ledger's reply. */
    @FunctionalInterface
    private interface OnReply {
        SagaDecision decide(boolean succeeded, String reason);
    }

    /** A ledger step: decide on the reply, or re-send the command if the reply is overdue. */
    private SagaDecision ledgerStep(PayoutSaga saga, SagaInput input, OnReply onReply) {
        return switch (input) {
            case SagaInput.LedgerAnswer answer -> onReply.decide(SUCCEEDED.equals(answer.outcome()), answer.reason());
            case SagaInput.TimerFired ignored -> SagaDecision.of("REPLY_OVERDUE", "Re-sending command " + saga.getPendingCommandId())
                    .resend().build();
            default -> throw unexpected(saga, input);
        };
    }

    private static SagaDecision retry(BankResponse response) {
        return SagaDecision.of("UNKNOWN", response.describe()).stay(Schedule.BACKOFF).build();
    }

    private static SagaDecision parked(String outcome, String reason) {
        return SagaDecision.of(outcome, reason).park(reason).build();
    }

    private static boolean expired(PayoutSaga saga, LocalDateTime now, Duration timeout) {
        return !now.isBefore(saga.getStepStartedAt().plus(timeout));
    }

    private static BankResponse bank(PayoutSaga saga, SagaInput input) {
        if (input instanceof SagaInput.BankAnswer answer) {
            return answer.response();
        }
        throw unexpected(saga, input);
    }

    private static IllegalStateException unexpected(PayoutSaga saga, SagaInput input) {
        return new IllegalStateException("Saga " + saga.getId() + " in " + saga.getState()
                + " can't handle " + input.getClass().getSimpleName());
    }
}
