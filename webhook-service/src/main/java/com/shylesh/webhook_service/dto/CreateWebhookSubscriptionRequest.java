package com.shylesh.webhook_service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateWebhookSubscriptionRequest(

        @NotNull
        UUID merchantId,

        @NotBlank
        @Size(max = 2048)
        String url
) {
}
