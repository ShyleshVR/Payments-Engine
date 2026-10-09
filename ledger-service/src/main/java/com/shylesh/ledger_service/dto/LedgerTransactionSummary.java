package com.shylesh.ledger_service.dto;

import com.shylesh.ledger_service.persistence.LedgerTransactionType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/** One ledger transaction with its amount (both entries of a transaction carry the same amount). */
public record LedgerTransactionSummary(
        UUID transactionId,
        UUID paymentId,
        UUID sagaId,
        LedgerTransactionType type,
        BigDecimal amount,
        String currency,
        LocalDateTime createdAt
) {
}
