package com.shylesh.ledger_service.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LedgerTransactionRepository extends JpaRepository<LedgerTransaction, UUID> {

    List<LedgerTransaction> findByPaymentIdOrderByCreatedAtAsc(UUID paymentId);

    List<LedgerTransaction> findBySagaIdOrderByCreatedAtAsc(UUID sagaId);

    Optional<LedgerTransaction> findFirstByPaymentIdAndType(UUID paymentId, LedgerTransactionType type);
}
