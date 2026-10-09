package com.shylesh.payment_service.saga;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The data of a ledger command (the message is the usual {eventId, eventType, occurredAt, data}
 * envelope). commandId stays the same when the command is re-sent, so the ledger handles it once.
 */
public record LedgerCommandMessage(
        UUID commandId,
        String commandType,
        UUID sagaId,
        UUID paymentId,
        UUID merchantId,
        BigDecimal amount,
        String currency
) {
}
