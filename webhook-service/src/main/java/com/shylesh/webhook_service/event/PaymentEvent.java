package com.shylesh.webhook_service.event;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A payment event that has passed validation: every field the webhook pipeline relies on is
 * present. Produced only by PaymentEventParser.
 */
public record PaymentEvent(
        UUID eventId,
        String eventType,
        LocalDateTime occurredAt,
        PaymentEventData data
) {
}
