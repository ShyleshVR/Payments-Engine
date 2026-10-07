package com.shylesh.webhook_service.service;

import com.shylesh.webhook_service.dto.WebhookDeliveryResponse;

import java.util.List;
import java.util.UUID;

public interface WebhookDeliveryQueryService {

    /** Only this merchant's deliveries for the payment (empty for anyone else's payment). */
    List<WebhookDeliveryResponse> findByPayment(UUID merchantId, UUID paymentId);
}
