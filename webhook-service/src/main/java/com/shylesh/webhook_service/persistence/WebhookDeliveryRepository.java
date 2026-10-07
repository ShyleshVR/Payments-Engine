package com.shylesh.webhook_service.persistence;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, UUID> {

    List<WebhookDelivery> findByStatusInAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
            List<WebhookDeliveryStatus> statuses,
            LocalDateTime now,
            Limit limit
    );

    /**
     * Locks the delivery only if it is still deliverable and due. Empty if another dispatcher
     * holds the row lock (SKIP LOCKED) or has already leased it (next_attempt_at pushed into
     * the future), so two instances can never claim the same attempt.
     */
    @Query(value = """
            SELECT * FROM webhook_deliveries
            WHERE id = :id
              AND status IN ('PENDING', 'RETRYING')
              AND next_attempt_at <= :now
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<WebhookDelivery> lockIfDue(@Param("id") UUID id, @Param("now") LocalDateTime now);

    @Query(value = """
            SELECT * FROM webhook_deliveries
            WHERE status = 'FAILED'
              AND dlt_published_at IS NULL
            ORDER BY updated_at
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<WebhookDelivery> lockNextUnpublishedDeadLetter();

    List<WebhookDelivery> findByPaymentIdOrderByCreatedAtAsc(UUID paymentId);

    /**
     * Cancels every open delivery of a subscription. Bumps version so an in-flight attempt's
     * outcome (recorded after its HTTP call) is detected as stale and discarded.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE WebhookDelivery d
               SET d.status = com.shylesh.webhook_service.persistence.WebhookDeliveryStatus.CANCELLED,
                   d.nextAttemptAt = null,
                   d.lastError = :reason,
                   d.updatedAt = :now,
                   d.version = d.version + 1
             WHERE d.subscriptionId = :subscriptionId
               AND d.status IN (com.shylesh.webhook_service.persistence.WebhookDeliveryStatus.PENDING,
                                com.shylesh.webhook_service.persistence.WebhookDeliveryStatus.RETRYING)
            """)
    int cancelOpenDeliveries(
            @Param("subscriptionId") UUID subscriptionId,
            @Param("reason") String reason,
            @Param("now") LocalDateTime now
    );
}
