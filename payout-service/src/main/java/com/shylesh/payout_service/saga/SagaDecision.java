package com.shylesh.payout_service.saga;

/**
 * What the state machine decided; the orchestrator applies it in one transaction.
 *
 * @param outcome       label for the saga's audit trail (HELD, ACCEPTED, PAID, UNKNOWN, ...)
 * @param moveTo        next state, or null to stay in the current step
 * @param sendCommand   a new ledger command to send (fresh command id), or null
 * @param resendCommand re-send the pending ledger command (same command id)
 * @param payoutChange  what happens to the payout (and which event is published)
 * @param code          failure code: of the payout change, or recorded on the saga for later
 * @param parkReason    non-null: stop for an operator (REQUIRES_ATTENTION)
 */
public record SagaDecision(
        String outcome,
        String detail,
        SagaState moveTo,
        Schedule schedule,
        LedgerCommandType sendCommand,
        boolean resendCommand,
        PayoutChange payoutChange,
        String code,
        String parkReason
) {

    static Builder of(String outcome, String detail) {
        return new Builder(outcome, detail);
    }

    static final class Builder {
        private final String outcome;
        private final String detail;
        private SagaState moveTo;
        private Schedule schedule = Schedule.NONE;
        private LedgerCommandType sendCommand;
        private boolean resendCommand;
        private PayoutChange payoutChange = PayoutChange.NONE;
        private String code;
        private String parkReason;

        private Builder(String outcome, String detail) {
            this.outcome = outcome;
            this.detail = detail;
        }

        Builder moveTo(SagaState state, Schedule schedule) {
            this.moveTo = state;
            this.schedule = schedule;
            return this;
        }

        Builder stay(Schedule schedule) {
            this.schedule = schedule;
            return this;
        }

        Builder send(LedgerCommandType command) {
            this.sendCommand = command;
            this.schedule = Schedule.REPLY_TIMEOUT;
            return this;
        }

        Builder resend() {
            this.resendCommand = true;
            this.schedule = Schedule.REPLY_TIMEOUT;
            return this;
        }

        Builder payout(PayoutChange change) {
            this.payoutChange = change;
            return this;
        }

        Builder code(String code) {
            this.code = code;
            return this;
        }

        Builder park(String reason) {
            this.parkReason = reason;
            this.schedule = Schedule.NONE;
            return this;
        }

        SagaDecision build() {
            return new SagaDecision(outcome, detail, moveTo, schedule, sendCommand, resendCommand, payoutChange, code, parkReason);
        }
    }
}
