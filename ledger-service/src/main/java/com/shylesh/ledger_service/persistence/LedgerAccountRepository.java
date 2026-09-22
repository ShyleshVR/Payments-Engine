package com.shylesh.ledger_service.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface LedgerAccountRepository extends JpaRepository<LedgerAccount, UUID> {

    Optional<LedgerAccount> findByOwnerTypeAndOwnerIdAndCurrency(
            LedgerAccountType ownerType, UUID ownerId, String currency
    );
}
