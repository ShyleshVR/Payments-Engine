package com.shylesh.webhook_service.event;

import java.time.LocalDateTime;
import java.util.UUID;

/** A payout event that has passed validation. Produced only by PayoutEventParser. */
public record PayoutEvent(
        UUID eventId,
        String eventType,
        LocalDateTime occurredAt,
        PayoutEventData data
) {
}
