package com.shylesh.ledger_service.service.impl;

import com.shylesh.ledger_service.dto.AccountBalanceResponse;
import com.shylesh.ledger_service.dto.LedgerEntryResponse;
import com.shylesh.ledger_service.dto.LedgerTransactionResponse;
import com.shylesh.ledger_service.persistence.*;
import com.shylesh.ledger_service.service.LedgerQueryService;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LedgerQueryServiceImpl implements LedgerQueryService {

    private final LedgerAccountRepository accountRepository;
    private final LedgerTransactionRepository transactionRepository;
    private final LedgerEntryRepository entryRepository;

    @Override
    public AccountBalanceResponse getBalance(LedgerAccountType ownerType, UUID ownerId, String currency) {

        BigDecimal balance = accountRepository
                .findByOwnerTypeAndOwnerIdAndCurrency(ownerType, ownerId, currency)
                .map(account -> entryRepository.sumBalanceByAccountId(account.getId()))
                .orElse(BigDecimal.ZERO);

        return AccountBalanceResponse.builder()
                .ownerType(ownerType)
                .ownerId(ownerId)
                .currency(currency)
                .balance(balance)
                .build();
    }

    @Override
    public List<LedgerTransactionResponse> getTransactionsForPayment(UUID paymentId) {
        List<LedgerTransaction> transactions = transactionRepository.findByPaymentIdOrderByCreatedAtAsc(paymentId);

        List<UUID> transactionIds = transactions.stream().map(LedgerTransaction::getId).toList();

        Map<UUID, List<LedgerEntry>> entriesByTransactionId = entryRepository
                .findByTransactionIdInOrderByCreatedAtAsc(transactionIds).stream()
                .collect(Collectors.groupingBy(LedgerEntry::getTransactionId));

        return transactions.stream()
                .map(transaction -> toResponse(
                        transaction,
                        entriesByTransactionId.getOrDefault(transaction.getId(), List.of())
                ))
                .toList();
    }

    private LedgerTransactionResponse toResponse(LedgerTransaction transaction, List<LedgerEntry> ledgerEntries) {
        List<LedgerEntryResponse> entries = ledgerEntries.stream()
                .map(entry -> LedgerEntryResponse.builder()
                        .accountId(entry.getAccountId())
                        .direction(entry.getDirection())
                        .amount(entry.getAmount())
                        .currency(entry.getCurrency())
                        .build())
                .toList();

        return LedgerTransactionResponse.builder()
                .transactionId(transaction.getId())
                .paymentId(transaction.getPaymentId())
                .type(transaction.getType())
                .createdAt(transaction.getCreatedAt())
                .entries(entries)
                .build();
    }
}
