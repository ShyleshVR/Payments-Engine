package com.shylesh.processor_simulator.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface TransferRepository extends JpaRepository<Transfer, String> {

    /**
     * Transfers whose next step (settle, fail, return) is due, locked; SKIP LOCKED lets every
     * replica run the bank's clock without two of them moving the same transfer.
     */
    @Query(value = """
            SELECT * FROM transfers
            WHERE next_transition_at IS NOT NULL AND next_transition_at <= :now
            ORDER BY next_transition_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Transfer> lockDue(@Param("now") LocalDateTime now, @Param("limit") int limit);

    /** Transfers created, paid, failed or returned in [from, to) (settlement report). */
    @Query("""
            SELECT t FROM Transfer t
            WHERE (t.createdAt >= :from AND t.createdAt < :to)
               OR (t.paidAt >= :from AND t.paidAt < :to)
               OR (t.failedAt >= :from AND t.failedAt < :to)
               OR (t.returnedAt >= :from AND t.returnedAt < :to)
            ORDER BY t.createdAt, t.id
            """)
    List<Transfer> findActiveBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
