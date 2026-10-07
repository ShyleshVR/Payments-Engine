package com.shylesh.webhook_service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The merchant is not part of the request: it is always taken from the caller's token. */
public record CreateWebhookSubscriptionRequest(
        @NotBlank @Size(max = 2048) String url
) {
}
