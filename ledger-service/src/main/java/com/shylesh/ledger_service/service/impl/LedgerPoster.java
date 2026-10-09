package com.shylesh.ledger_service.service.impl;

import com.shylesh.ledger_service.persistence.*;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Writes one balanced transaction: a debit and a credit of the same amount (double entry). */
@Component
@RequiredArgsConstructor
public class LedgerPoster {

    private final LedgerTransactionRepository transactionRepository;
    private final LedgerEntryRepository entryRepository;
    private final MeterRegistry meterRegistry;

    /**
     * @param sourceId the command that caused the posting; unique, so the same command can never
     *                 post twice even if its deduplication record were missing
     */
    public LedgerTransaction post(UUID sourceId, UUID paymentId, UUID sagaId, LedgerTransactionType type,
                                  UUID debitAccountId, UUID creditAccountId, BigDecimal amount, String currency) {
        LocalDateTime now = LocalDateTime.now();
        LedgerTransaction transaction = transactionRepository.save(
                LedgerTransaction.builder()
                        .id(UUID.randomUUID())
                        .eventId(sourceId)
                        .paymentId(paymentId)
                        .sagaId(sagaId)
                        .type(type)
                        .createdAt(now)
                        .build()
        );
        entryRepository.saveAll(List.of(
                entry(transaction.getId(), debitAccountId, LedgerDirection.DEBIT, amount, currency, now),
                entry(transaction.getId(), creditAccountId, LedgerDirection.CREDIT, amount, currency, now)
        ));

        Counter.builder("ledger.transactions.posted")
                .tag("type", type.name())
                .register(meterRegistry)
                .increment();
        return transaction;
    }

    private static LedgerEntry entry(UUID transactionId, UUID accountId, LedgerDirection direction,
                                     BigDecimal amount, String currency, LocalDateTime now) {
        return LedgerEntry.builder()
                .id(UUID.randomUUID())
                .transactionId(transactionId)
                .accountId(accountId)
                .direction(direction)
                .amount(amount)
                .currency(currency)
                .createdAt(now)
                .build();
    }
}
