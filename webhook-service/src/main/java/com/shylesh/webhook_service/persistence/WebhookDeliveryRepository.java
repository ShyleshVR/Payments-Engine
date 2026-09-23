package com.shylesh.webhook_service.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, UUID> {

    List<WebhookDelivery> findTop100ByStatusInAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
            List<WebhookDeliveryStatus> statuses,
            LocalDateTime now
    );

    List<WebhookDelivery> findByPaymentIdOrderByCreatedAtAsc(UUID paymentId);
}
