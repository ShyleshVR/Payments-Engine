package com.shylesh.payment_service.saga;

import com.shylesh.payment_service.entity.PaymentStatus;

/**
 * What the state machine decided; the orchestrator applies it in one transaction.
 *
 * @param outcome       label for the saga's audit trail (APPROVED, DECLINED, UNKNOWN, ...)
 * @param moveTo        next state, or null to stay in the current step
 * @param sendCommand   a new ledger command to send (fresh command id), or null
 * @param resendCommand re-send the pending ledger command (same command id)
 * @param compensateTo  entering VOIDING: the payment status once the authorization is released
 * @param code          failure code for the payment change, compensation or refund
 * @param parkReason    non-null: stop for an operator (REQUIRES_ATTENTION)
 */
public record SagaDecision(
        String outcome,
        String detail,
        SagaState moveTo,
        Schedule schedule,
        LedgerCommandType sendCommand,
        boolean resendCommand,
        PaymentChange paymentChange,
        PaymentStatus compensateTo,
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
        private PaymentChange paymentChange = PaymentChange.NONE;
        private PaymentStatus compensateTo;
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
            return this;
        }

        Builder resend() {
            this.resendCommand = true;
            this.schedule = Schedule.REPLY_TIMEOUT;
            return this;
        }

        Builder payment(PaymentChange change, String code) {
            this.paymentChange = change;
            this.code = code;
            return this;
        }

        Builder compensate(PaymentStatus resolution, String code) {
            this.compensateTo = resolution;
            this.code = code;
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
            return new SagaDecision(outcome, detail, moveTo, schedule, sendCommand, resendCommand,
                    paymentChange, compensateTo, code, parkReason);
        }
    }
}
