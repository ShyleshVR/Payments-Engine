package com.shylesh.webhook_service.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface WebhookDeliveryAttemptRepository extends JpaRepository<WebhookDeliveryAttempt, UUID> {

    List<WebhookDeliveryAttempt> findByDeliveryIdInOrderByAttemptNumberAsc(Collection<UUID> deliveryIds);
}
