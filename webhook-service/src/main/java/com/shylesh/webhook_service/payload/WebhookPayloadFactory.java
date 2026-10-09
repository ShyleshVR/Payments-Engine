package com.shylesh.webhook_service.payload;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shylesh.webhook_service.event.PaymentEvent;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
@RequiredArgsConstructor
public class WebhookPayloadFactory {

    /** 1.1 added failureCode (additive: 1.0 consumers can ignore it). */
    public static final String CURRENT_PAYLOAD_VERSION = "1.1";

    private static final String PUBLIC_PAYMENT_ID_PREFIX = "pay_";

    private final ObjectMapper objectMapper;

    public WebhookPayload create(PaymentEvent event) {
        // occurredAt is UTC without an offset on the internal wire; merchants get an explicit
        // ISO-8601 instant ("...Z") so there is no timezone guesswork on their side.
        String occurredAt = event.occurredAt() == null
                ? null
                : DateTimeFormatter.ISO_INSTANT.format(event.occurredAt().toInstant(ZoneOffset.UTC));

        return new WebhookPayload(
                CURRENT_PAYLOAD_VERSION,
                event.eventId(),
                event.eventType(),
                PUBLIC_PAYMENT_ID_PREFIX + event.data().getPaymentId(),
                event.data().getMerchantId(),
                event.data().getAmount(),
                event.data().getCurrency(),
                occurredAt,
                event.data().getFailureCode()
        );
    }

    /** Renders the exact bytes that are stored, signed and sent. */
    public String render(WebhookPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize webhook payload for event " + payload.eventId(), e);
        }
    }
}
