package com.shylesh.webhook_service.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, UUID> {

    List<WebhookDelivery> findTop100ByStatusInAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
            List<WebhookDeliveryStatus> statuses,
            LocalDateTime now
    );

    List<WebhookDelivery> findByPaymentIdOrderByCreatedAtAsc(UUID paymentId);

    /** Dead letters not yet confirmed by Kafka, oldest failure first. */
    List<WebhookDelivery> findTop100ByStatusAndDltPublishedAtIsNullOrderByUpdatedAtAsc(WebhookDeliveryStatus status);

    /**
     * Stamps the dead letter as confirmed. A direct update (rather than load + save) so
     * updated_at keeps meaning "when the delivery failed".
     *
     * @return 1 if this call stamped it, 0 if it was already stamped
     */
    @Transactional
    @Modifying
    @Query("update WebhookDelivery d set d.dltPublishedAt = :publishedAt "
            + "where d.id = :id and d.dltPublishedAt is null")
    int markDeadLetterPublished(@Param("id") UUID id, @Param("publishedAt") LocalDateTime publishedAt);
}
