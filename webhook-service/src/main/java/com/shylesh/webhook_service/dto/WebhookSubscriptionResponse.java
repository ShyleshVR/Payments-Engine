package com.shylesh.webhook_service.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.shylesh.webhook_service.persistence.MerchantWebhookSubscription;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * @param secret only present in the response to creation; it is never returned again
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WebhookSubscriptionResponse(
        UUID subscriptionId,
        UUID merchantId,
        String url,
        boolean active,
        LocalDateTime createdAt,
        String secret
) {

    public static WebhookSubscriptionResponse withSecret(MerchantWebhookSubscription subscription) {
        return of(subscription, subscription.getSecret());
    }

    public static WebhookSubscriptionResponse withoutSecret(MerchantWebhookSubscription subscription) {
        return of(subscription, null);
    }

    private static WebhookSubscriptionResponse of(MerchantWebhookSubscription subscription, String secret) {
        return new WebhookSubscriptionResponse(
                subscription.getId(),
                subscription.getMerchantId(),
                subscription.getUrl(),
                subscription.isActive(),
                subscription.getCreatedAt(),
                secret
        );
    }
}
