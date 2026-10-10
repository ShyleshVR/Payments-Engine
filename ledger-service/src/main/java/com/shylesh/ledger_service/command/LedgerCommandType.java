package com.shylesh.ledger_service.command;

import com.shylesh.ledger_service.config.LedgerTopics;

/**
 * What the saga orchestrators can ask the ledger to do: payment-service for payments and
 * refunds, payout-service for payouts. Each command type's reply goes to its orchestrator's
 * reply topic, so neither sees the other's replies.
 */
public enum LedgerCommandType {

    /** Captured payment: debit platform clearing, credit the merchant. */
    SETTLE_PAYMENT(false),
    /** Refund step 1: move the amount from the merchant's balance into their refund reserve, if it covers it. */
    HOLD_REFUND(false),
    /** Refund compensation: the processor refused the refund, move the reserved amount back. */
    RELEASE_HOLD(false),
    /** Refund done at the processor: the reserved amount leaves through platform clearing. */
    FINALIZE_REFUND(false),

    /** Payout step 1: move the amount into the merchant's payout reserve, if their payable balance covers it. */
    HOLD_PAYOUT(true),
    /** Payout compensation: the bank rejected or failed the transfer, move the reserved amount back. */
    RELEASE_PAYOUT(true),
    /** The bank paid the transfer: the reserved amount leaves through payout clearing. */
    FINALIZE_PAYOUT(true),
    /** A paid transfer came back from the merchant's bank: credit the amount back to their balance. */
    RETURN_PAYOUT(true);

    private final boolean payout;

    LedgerCommandType(boolean payout) {
        this.payout = payout;
    }

    /** A payout command (carries a payout id), or a payment command (carries a payment id). */
    public boolean isPayout() {
        return payout;
    }

    public String replyTopic() {
        return payout ? LedgerTopics.PAYOUT_REPLIES : LedgerTopics.REPLIES;
    }
}
