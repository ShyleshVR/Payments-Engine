package com.shylesh.webhook_service.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.shylesh.webhook_service.persistence.MerchantWebhookSubscription;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * secret is only populated in the create response — it is shown once and never returned
 * again by any read endpoint.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WebhookSubscriptionResponse(
        UUID id,
        UUID merchantId,
        String url,
        boolean active,
        LocalDateTime createdAt,
        String secret
) {

    public static WebhookSubscriptionResponse withoutSecret(MerchantWebhookSubscription subscription) {
        return new WebhookSubscriptionResponse(
                subscription.getId(),
                subscription.getMerchantId(),
                subscription.getUrl(),
                subscription.isActive(),
                subscription.getCreatedAt(),
                null
        );
    }

    public static WebhookSubscriptionResponse withSecret(MerchantWebhookSubscription subscription) {
        return new WebhookSubscriptionResponse(
                subscription.getId(),
                subscription.getMerchantId(),
                subscription.getUrl(),
                subscription.isActive(),
                subscription.getCreatedAt(),
                subscription.getSecret()
        );
    }
}
