package com.shylesh.ledger_service.service.impl;

import com.shylesh.ledger_service.persistence.LedgerAccount;
import com.shylesh.ledger_service.persistence.LedgerAccountRepository;
import com.shylesh.ledger_service.persistence.LedgerAccountType;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Finds or lazily creates the ledger account for a given owner. The Kafka listener runs with
 * a single consumer thread by default, so a plain find-then-create is safe here; the unique
 * constraints on ledger_accounts remain as defense-in-depth against that assumption changing.
 */
@Component
@RequiredArgsConstructor
public class LedgerAccountResolver {

    private final LedgerAccountRepository accountRepository;

    public LedgerAccount resolve(LedgerAccountType ownerType, UUID ownerId, String currency) {
        return accountRepository.findByOwnerTypeAndOwnerIdAndCurrency(ownerType, ownerId, currency)
                .orElseGet(() -> accountRepository.save(
                        LedgerAccount.builder()
                                .id(UUID.randomUUID())
                                .ownerType(ownerType)
                                .ownerId(ownerId)
                                .currency(currency)
                                .createdAt(LocalDateTime.now())
                                .build()
                ));
    }
}
