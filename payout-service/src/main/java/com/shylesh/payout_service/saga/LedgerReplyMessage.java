package com.shylesh.payout_service.saga;

import java.util.UUID;

/** The ledger's answer to a payout command (outcome SUCCEEDED, or REJECTED with a reason). */
public record LedgerReplyMessage(
        UUID commandId,
        String commandType,
        UUID sagaId,
        UUID payoutId,
        String outcome,
        String reason,
        UUID transactionId
) {
}
