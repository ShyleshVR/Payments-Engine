package com.shylesh.ledger_service.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    List<LedgerEntry> findByTransactionIdOrderByCreatedAtAsc(UUID transactionId);

    List<LedgerEntry> findByTransactionIdInOrderByCreatedAtAsc(List<UUID> transactionIds);

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

    /**
     * Settlement credits to the account at or after the cutoff: money not yet available for
     * payout. The payable balance is the balance minus these, so a payout only ever pays out
     * money settled before the cutoff (and everything else that came back: releases, returns).
     */
    @Query("""
            SELECT COALESCE(SUM(e.amount), 0)
            FROM LedgerEntry e, LedgerTransaction t
            WHERE e.transactionId = t.id
              AND e.accountId = :accountId
              AND e.direction = com.shylesh.ledger_service.persistence.LedgerDirection.CREDIT
              AND t.type = com.shylesh.ledger_service.persistence.LedgerTransactionType.SETTLEMENT
              AND t.createdAt >= :cutoff
            """)
    BigDecimal sumSettlementCreditsSince(@Param("accountId") UUID accountId, @Param("cutoff") LocalDateTime cutoff);

    /** A merchant's payable balance in one currency (see sumSettlementCreditsSince). */
    interface PayableBalance {
        UUID getMerchantId();

        String getCurrency();

        BigDecimal getPayable();
    }

    /** Every merchant balance whose payable part is at least the minimum (the daily payout batch). */
    @Query(value = """
            SELECT a.owner_id AS merchantId, a.currency AS currency,
                   SUM(CASE WHEN e.direction = 'CREDIT' THEN e.amount ELSE -e.amount END)
                 - SUM(CASE WHEN e.direction = 'CREDIT' AND t.type = 'SETTLEMENT' AND t.created_at >= :cutoff
                            THEN e.amount ELSE 0 END) AS payable
            FROM ledger_accounts a
            JOIN ledger_entries e ON e.account_id = a.id
            JOIN ledger_transactions t ON t.id = e.transaction_id
            WHERE a.owner_type = 'MERCHANT'
            GROUP BY a.owner_id, a.currency
            HAVING SUM(CASE WHEN e.direction = 'CREDIT' THEN e.amount ELSE -e.amount END)
                 - SUM(CASE WHEN e.direction = 'CREDIT' AND t.type = 'SETTLEMENT' AND t.created_at >= :cutoff
                            THEN e.amount ELSE 0 END) >= :minimum
            ORDER BY a.owner_id, a.currency
            """, nativeQuery = true)
    List<PayableBalance> findPayableBalances(@Param("cutoff") LocalDateTime cutoff, @Param("minimum") BigDecimal minimum);
}
