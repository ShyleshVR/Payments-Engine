package com.shylesh.webhook_service.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface MerchantWebhookSubscriptionRepository extends JpaRepository<MerchantWebhookSubscription, UUID> {

    Optional<MerchantWebhookSubscription> findByMerchantIdAndActiveTrue(UUID merchantId);
}
