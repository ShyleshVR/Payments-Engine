package com.shylesh.ledger_service.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Locks up to :limit of the oldest due messages that are each the earliest unpublished one of
     * their payment (so a batch never holds two of one payment). SKIP LOCKED lets every replica
     * relay side by side; the guard keeps each payment's replies in commit order on its Kafka
     * partition.
     * The guard is "this event's seq is the lowest unpublished one of its aggregate", written as
     * a correlated min() rather than NOT EXISTS: Postgres turns NOT EXISTS into an anti-join, and
     * with few pending rows among millions it chose one that scanned the whole index once per
     * candidate, which made claims slower exactly as the backlog grew. min() runs per candidate as
     * a one-entry lookup on (aggregate_id, seq).
     */
    @Query(value = """
            SELECT e.* FROM outbox_event e
            WHERE e.status = 'PENDING'
              AND (e.next_attempt_at IS NULL OR e.next_attempt_at <= :now)
              AND e.seq = (
                  SELECT min(earlier.seq) FROM outbox_event earlier
                  WHERE earlier.aggregate_id = e.aggregate_id
                    AND earlier.status <> 'PUBLISHED'
              )
            ORDER BY e.seq
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> claimPublishable(@Param("now") LocalDateTime now, @Param("limit") int limit);

    @Modifying
    @Query("DELETE FROM OutboxEvent e WHERE e.status = :status AND e.publishedAt < :cutoff")
    int deleteByStatusAndPublishedAtBefore(@Param("status") OutboxEventStatus status,
                                           @Param("cutoff") LocalDateTime cutoff);

    long countByStatus(OutboxEventStatus status);

    /** Creation time of the oldest message still waiting to be published (null if none). */
    @Query("SELECT MIN(e.createdAt) FROM OutboxEvent e WHERE e.status = :status")
    LocalDateTime findOldestCreatedAt(@Param("status") OutboxEventStatus status);
}
