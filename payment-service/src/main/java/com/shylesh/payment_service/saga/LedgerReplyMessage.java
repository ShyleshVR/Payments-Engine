package com.shylesh.payment_service.saga;

import java.util.UUID;

/** The data of a ledger reply (see ledger-service's LedgerReply). */
public record LedgerReplyMessage(
        UUID commandId,
        String commandType,
        UUID sagaId,
        UUID paymentId,
        String outcome,
        String reason,
        UUID transactionId
) {
}
