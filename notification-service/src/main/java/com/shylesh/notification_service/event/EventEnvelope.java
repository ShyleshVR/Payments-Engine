package com.shylesh.notification_service.event;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Mirrors payment-service's EventEnvelope wire shape ({eventId, eventType, occurredAt, data}).
 * Duplicated deliberately rather than shared — a shared base-api module is future work and
 * isn't justified yet for a single four-field wrapper.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class EventEnvelope {

    private UUID eventId;

    private String eventType;

    private LocalDateTime occurredAt;

    private JsonNode data;
}
