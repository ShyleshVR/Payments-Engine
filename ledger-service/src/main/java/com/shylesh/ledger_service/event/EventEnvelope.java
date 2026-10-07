package com.shylesh.ledger_service.event;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Wire shape of payment-service events ({eventId, eventType, occurredAt, data}); occurredAt is UTC.
 *
 * data is typed rather than generic or a JsonNode on purpose: Jackson then builds amount as a
 * BigDecimal straight from the JSON text. Going through a Map or JsonNode turns decimals into
 * doubles, which can post the wrong amount to the ledger for large values.
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
