package com.shylesh.webhook_service.payload;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The merchant-facing body of a payout webhook (its own contract, versioned separately from
 * payment webhooks). payoutId uses the public "po_" form, as the payout API returns it.
 */
@JsonPropertyOrder({"payloadVersion", "eventId", "eventType", "payoutId", "merchantId", "amount", "currency", "status",
        "occurredAt", "failureCode"})
public record PayoutWebhookPayload(
        String payloadVersion,
        UUID eventId,
        String eventType,
        String payoutId,
        UUID merchantId,
        BigDecimal amount,
        String currency,
        String status,
        String occurredAt,
        // why a payout failed or was returned; absent otherwise
        @JsonInclude(JsonInclude.Include.NON_NULL) String failureCode
) {
}
