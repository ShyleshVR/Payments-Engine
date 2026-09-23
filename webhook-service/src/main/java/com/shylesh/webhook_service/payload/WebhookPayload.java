package com.shylesh.webhook_service.payload;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The public, merchant-facing webhook body. Deliberately separate from the internal
 * EventEnvelope/PaymentEventData so internal event changes never leak into merchants'
 * integrations by accident.
 *
 * payloadVersion is a string so it can go "1" -> "1.1" -> "2" without implying numeric
 * ordering guarantees. A breaking change ships as a new version merchants opt into.
 */
@JsonPropertyOrder({
        "payloadVersion", "eventId", "eventType", "paymentId",
        "merchantId", "amount", "currency", "occurredAt"
})
public record WebhookPayload(
        String payloadVersion,
        UUID eventId,
        String eventType,
        UUID paymentId,
        UUID merchantId,
        BigDecimal amount,
        String currency,
        /** ISO-8601 UTC instant, e.g. 2026-09-23T04:12:14.461Z */
        String occurredAt
) {
}
