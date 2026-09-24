package com.shylesh.webhook_service.service;

import com.shylesh.webhook_service.dto.CreateWebhookSubscriptionRequest;
import com.shylesh.webhook_service.dto.WebhookSubscriptionResponse;

import java.util.UUID;

public interface WebhookSubscriptionService {

    /** Creates the merchant's subscription; the response is the only time the secret is exposed. */
    WebhookSubscriptionResponse create(CreateWebhookSubscriptionRequest request);

    WebhookSubscriptionResponse getActive(UUID merchantId);

    void deactivate(UUID merchantId);
}
