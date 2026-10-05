package com.shylesh.notification_service.persistance;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    List<Notification> findTop100ByStatusInAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
            List<NotificationStatus> statuses,
            LocalDateTime now
    );

    /**
     * Locks the notification only if it is still deliverable and due. Returns empty if another
     * dispatcher holds the row lock (SKIP LOCKED) or has already leased it (next_attempt_at
     * pushed into the future), so two instances can never claim the same attempt.
     */
    @Query(value = """
            SELECT * FROM notifications
            WHERE id = :id
              AND status IN ('PENDING', 'RETRYING')
              AND next_attempt_at <= :now
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<Notification> lockIfDue(@Param("id") UUID id, @Param("now") LocalDateTime now);

    @Query(value = """
            SELECT * FROM notifications
            WHERE status = 'FAILED'
              AND dlt_published_at IS NULL
            ORDER BY updated_at
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<Notification> lockNextUnpublishedDeadLetter();
}
