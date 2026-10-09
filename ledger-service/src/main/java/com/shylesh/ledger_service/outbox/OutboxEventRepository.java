package com.shylesh.ledger_service.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Locks the oldest due message that is the earliest unpublished one of its payment. SKIP
     * LOCKED lets every replica relay side by side; the NOT EXISTS guard keeps each payment's
     * replies in commit order on its Kafka partition.
     */
    @Query(value = """
            SELECT e.* FROM outbox_event e
            WHERE e.status = 'PENDING'
              AND (e.next_attempt_at IS NULL OR e.next_attempt_at <= :now)
              AND NOT EXISTS (
                  SELECT 1 FROM outbox_event earlier
                  WHERE earlier.aggregate_id = e.aggregate_id
                    AND earlier.status <> 'PUBLISHED'
                    AND earlier.seq < e.seq
              )
            ORDER BY e.seq
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<OutboxEvent> claimNextPublishable(@Param("now") LocalDateTime now);

    @Modifying
    @Query("DELETE FROM OutboxEvent e WHERE e.status = :status AND e.publishedAt < :cutoff")
    int deleteByStatusAndPublishedAtBefore(@Param("status") OutboxEventStatus status,
                                           @Param("cutoff") LocalDateTime cutoff);
}
