package com.shylesh.ledger_service.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    List<LedgerEntry> findByTransactionIdOrderByCreatedAtAsc(UUID transactionId);

    /**
     * Balance is derived from the entry log, never stored: SUM(credits) - SUM(debits).
     * A credit-only account (e.g. a merchant payable) reads as a positive "amount owed";
     * an account that is mostly debited (e.g. platform clearing) reads negative, since the
     * two sides of every transaction net to zero across the ledger as a whole.
     */
    @Query("""
            SELECT COALESCE(SUM(
                CASE WHEN e.direction = com.shylesh.ledger_service.persistence.LedgerDirection.CREDIT
                     THEN e.amount ELSE -e.amount END
            ), 0)
            FROM LedgerEntry e
            WHERE e.accountId = :accountId
            """)
    BigDecimal sumBalanceByAccountId(@Param("accountId") UUID accountId);
}
