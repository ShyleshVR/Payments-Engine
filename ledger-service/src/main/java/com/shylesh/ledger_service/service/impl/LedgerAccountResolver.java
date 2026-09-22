package com.shylesh.ledger_service.service.impl;

import com.shylesh.ledger_service.persistence.LedgerAccount;
import com.shylesh.ledger_service.persistence.LedgerAccountRepository;
import com.shylesh.ledger_service.persistence.LedgerAccountType;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Finds or lazily creates the ledger account for a given owner. Safe under concurrent callers
 * (multiple consumer threads or ledger-service instances) because account creation is guarded
 * by a Postgres advisory lock keyed on the owner tuple, so only one caller ever creates a given
 * account; the unique constraints on ledger_accounts remain as defense-in-depth.
 */
@Component
@RequiredArgsConstructor
public class LedgerAccountResolver {

    private final LedgerAccountRepository accountRepository;

    public LedgerAccount resolve(LedgerAccountType ownerType, UUID ownerId, String currency) {
        return accountRepository.findByOwnerTypeAndOwnerIdAndCurrency(ownerType, ownerId, currency)
                .orElseGet(() -> {
                    accountRepository.acquireCreationLock(lockKey(ownerType, ownerId, currency));

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
                });
    }

    private String lockKey(LedgerAccountType ownerType, UUID ownerId, String currency) {
        return ownerType + ":" + ownerId + ":" + currency;
    }
}
