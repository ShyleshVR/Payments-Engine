package com.shylesh.webhook_service.service;

import com.shylesh.webhook_service.dto.WebhookDeliveryResponse;

import java.util.List;
import java.util.UUID;

public interface WebhookDeliveryQueryService {

    List<WebhookDeliveryResponse> findByPaymentId(UUID paymentId);
}
