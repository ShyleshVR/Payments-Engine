package com.shylesh.ledger_service.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import com.shylesh.ledger_service.dto.LedgerTransactionSummary;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LedgerTransactionRepository extends JpaRepository<LedgerTransaction, UUID> {

    List<LedgerTransaction> findByPaymentIdOrderByCreatedAtAsc(UUID paymentId);

    List<LedgerTransaction> findBySagaIdOrderByCreatedAtAsc(UUID sagaId);

    Optional<LedgerTransaction> findFirstByPaymentIdAndType(UUID paymentId, LedgerTransactionType type);

    Optional<LedgerTransaction> findFirstByPayoutIdAndType(UUID payoutId, LedgerTransactionType type);

    List<LedgerTransaction> findByPayoutIdOrderByCreatedAtAsc(UUID payoutId);

    /** Transactions created in [from, to) with their amount, oldest first (reconciliation). */
    @Query("""
            SELECT new com.shylesh.ledger_service.dto.LedgerTransactionSummary(
                       t.id, t.paymentId, t.payoutId, t.sagaId, t.type, e.amount, e.currency, t.createdAt)
            FROM LedgerTransaction t, LedgerEntry e
            WHERE e.transactionId = t.id
              AND e.direction = com.shylesh.ledger_service.persistence.LedgerDirection.DEBIT
              AND t.createdAt >= :from AND t.createdAt < :to
            ORDER BY t.createdAt, t.id
            """)
    Slice<LedgerTransactionSummary> findSummariesBetween(@Param("from") LocalDateTime from,
                                                         @Param("to") LocalDateTime to, Pageable pageable);

    /** Refund holds created before the cutoff that were neither released nor turned into a refund. */
    default List<LedgerTransactionSummary> findOpenRefundHoldsCreatedBefore(LocalDateTime before) {
        return findOpenHoldsCreatedBefore(LedgerTransactionType.REFUND_HOLD,
                List.of(LedgerTransactionType.REFUND, LedgerTransactionType.REFUND_HOLD_RELEASE), before);
    }

    /** Payout holds created before the cutoff that were neither released nor paid out. */
    default List<LedgerTransactionSummary> findOpenPayoutHoldsCreatedBefore(LocalDateTime before) {
        return findOpenHoldsCreatedBefore(LedgerTransactionType.PAYOUT_HOLD,
                List.of(LedgerTransactionType.PAYOUT, LedgerTransactionType.PAYOUT_HOLD_RELEASE), before);
    }

    /** Holds of a type, created before the cutoff, that their saga never closed with one of the closing types. */
    @Query("""
            SELECT new com.shylesh.ledger_service.dto.LedgerTransactionSummary(
                       t.id, t.paymentId, t.payoutId, t.sagaId, t.type, e.amount, e.currency, t.createdAt)
            FROM LedgerTransaction t, LedgerEntry e
            WHERE e.transactionId = t.id
              AND e.direction = com.shylesh.ledger_service.persistence.LedgerDirection.DEBIT
              AND t.type = :holdType
              AND t.createdAt < :before
              AND NOT EXISTS (
                  SELECT 1 FROM LedgerTransaction x
                  WHERE x.sagaId = t.sagaId
                    AND x.type IN :closingTypes)
            ORDER BY t.createdAt
            """)
    List<LedgerTransactionSummary> findOpenHoldsCreatedBefore(@Param("holdType") LedgerTransactionType holdType,
                                                              @Param("closingTypes") List<LedgerTransactionType> closingTypes,
                                                              @Param("before") LocalDateTime before);
}
