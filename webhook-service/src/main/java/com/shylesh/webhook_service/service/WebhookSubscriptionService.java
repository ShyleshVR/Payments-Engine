package com.shylesh.webhook_service.service;

import com.shylesh.webhook_service.dto.CreateWebhookSubscriptionRequest;
import com.shylesh.webhook_service.dto.WebhookSubscriptionResponse;

import java.util.UUID;

public interface WebhookSubscriptionService {

    WebhookSubscriptionResponse create(CreateWebhookSubscriptionRequest request);

    WebhookSubscriptionResponse get(UUID merchantId);

    void deactivate(UUID merchantId);
}
