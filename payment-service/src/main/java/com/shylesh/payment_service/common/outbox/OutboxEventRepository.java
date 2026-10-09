package com.shylesh.payment_service.common.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface OutboxEventRepository
        extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Locks the oldest event that is due and is the earliest unpublished event of its
     * aggregate. SKIP LOCKED lets several publisher instances run side by side, each claiming
     * different rows. The NOT EXISTS guard means a payment's later events wait behind an
     * earlier one that is still retrying (or parked as FAILED), so per-payment order on the
     * Kafka partition always matches commit order.
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
    int deleteByStatusAndPublishedAtBefore(
            @Param("status") OutboxEventStatus status,
            @Param("cutoff") LocalDateTime cutoff
    );

    long countByStatus(OutboxEventStatus status);

    /** Creation time of the oldest message still waiting to be published (null if none). */
    @Query("SELECT MIN(e.createdAt) FROM OutboxEvent e WHERE e.status = :status")
    LocalDateTime findOldestCreatedAt(@Param("status") OutboxEventStatus status);
}
