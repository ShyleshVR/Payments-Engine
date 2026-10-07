package com.shylesh.notification_service.event;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Wire shape of payment-service events ({eventId, eventType, occurredAt, data}); occurredAt is UTC.
 * Duplicated per consumer for now: a shared event-contract module is in the backlog.
 *
 * data is typed rather than a JsonNode: Jackson then builds amount as a BigDecimal straight from
 * the JSON text instead of passing it through a double.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class EventEnvelope {

    private UUID eventId;

    private String eventType;

    private LocalDateTime occurredAt;

    private PaymentEventData data;
}
