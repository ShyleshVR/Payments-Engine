package com.shylesh.webhook_service.payload;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The merchant-facing webhook body. This is a public contract: payloadVersion is a string so
 * it can move "1" -> "1.1" -> "2" without implying numeric ordering, and a breaking change
 * ships as a new version merchants opt into instead of silently changing this one.
 *
 * paymentId uses the public "pay_" form, the same identifier the payment API returns, so
 * merchants can correlate webhooks with their API calls.
 */
@JsonPropertyOrder({"payloadVersion", "eventId", "eventType", "paymentId", "merchantId", "amount", "currency", "occurredAt"})
public record WebhookPayload(
        String payloadVersion,
        UUID eventId,
        String eventType,
        String paymentId,
        UUID merchantId,
        BigDecimal amount,
        String currency,
        String occurredAt
) {
}
