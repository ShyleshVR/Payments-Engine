package com.shylesh.ledger_service.command;

import java.util.UUID;

/**
 * The ledger's answer to one command.
 *
 * @param outcome       SUCCEEDED, or REJECTED with a reason (a business answer, not an error)
 * @param transactionId the ledger transaction posted (or found, for a repeated settlement)
 */
public record LedgerReply(
        UUID commandId,
        String commandType,
        UUID sagaId,
        UUID paymentId,
        String outcome,
        String reason,
        UUID transactionId
) {

    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String REJECTED = "REJECTED";

    public static LedgerReply succeeded(LedgerCommand command, UUID transactionId) {
        return new LedgerReply(command.getCommandId(), command.getCommandType(), command.getSagaId(),
                command.getPaymentId(), SUCCEEDED, null, transactionId);
    }

    public static LedgerReply rejected(LedgerCommand command, String reason) {
        return new LedgerReply(command.getCommandId(), command.getCommandType(), command.getSagaId(),
                command.getPaymentId(), REJECTED, reason, null);
    }
}
