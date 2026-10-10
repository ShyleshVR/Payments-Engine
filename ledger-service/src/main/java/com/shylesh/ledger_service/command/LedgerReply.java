package com.shylesh.ledger_service.command;

import java.util.UUID;

/**
 * The ledger's answer to one command.
 *
 * @param paymentId     the payment, for a payment command (null for a payout command)
 * @param payoutId      the payout, for a payout command (null for a payment command)
 * @param outcome       SUCCEEDED, or REJECTED with a reason (a business answer, not an error)
 * @param transactionId the ledger transaction posted (or found, for a repeated settlement or hold)
 */
public record LedgerReply(
        UUID commandId,
        String commandType,
        UUID sagaId,
        UUID paymentId,
        UUID payoutId,
        String outcome,
        String reason,
        UUID transactionId
) {

    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String REJECTED = "REJECTED";

    public static LedgerReply succeeded(LedgerCommand command, UUID transactionId) {
        return new LedgerReply(command.getCommandId(), command.getCommandType(), command.getSagaId(),
                command.getPaymentId(), command.getPayoutId(), SUCCEEDED, null, transactionId);
    }

    public static LedgerReply rejected(LedgerCommand command, String reason) {
        return new LedgerReply(command.getCommandId(), command.getCommandType(), command.getSagaId(),
                command.getPaymentId(), command.getPayoutId(), REJECTED, reason, null);
    }

    /** The payment or payout the reply is about: its Kafka key. */
    public UUID subjectId() {
        return paymentId != null ? paymentId : payoutId;
    }

    public String replyTopic() {
        return LedgerCommandType.valueOf(commandType).replyTopic();
    }
}
