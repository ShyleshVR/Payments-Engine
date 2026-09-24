package com.shylesh.webhook_service.payload;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shylesh.webhook_service.event.EventEnvelope;
import com.shylesh.webhook_service.event.PaymentEventData;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
@RequiredArgsConstructor
public class WebhookPayloadFactory {

    public static final String CURRENT_PAYLOAD_VERSION = "1";

    private final ObjectMapper objectMapper;

    public WebhookPayload create(EventEnvelope envelope, PaymentEventData data) {
        String occurredAt = envelope.getOccurredAt() == null
                ? null
                : DateTimeFormatter.ISO_INSTANT.format(envelope.getOccurredAt().toInstant(ZoneOffset.UTC));

        return new WebhookPayload(
                CURRENT_PAYLOAD_VERSION,
                envelope.getEventId(),
                envelope.getEventType(),
                data.getPaymentId(),
                data.getMerchantId(),
                data.getAmount(),
                data.getCurrency(),
                occurredAt
        );
    }

    /** Renders the exact bytes that get signed and sent. */
    public String render(WebhookPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize webhook payload for event " + payload.eventId(), e);
        }
    }
}
