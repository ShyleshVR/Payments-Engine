package com.shylesh.webhook_service.payload;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shylesh.webhook_service.event.PaymentEvent;
import com.shylesh.webhook_service.event.PayoutEvent;

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

    /** Payout webhooks have their own contract, versioned separately. */
    public static final String CURRENT_PAYOUT_PAYLOAD_VERSION = "1.0";

    private static final String PUBLIC_PAYOUT_ID_PREFIX = "po_";

    private final ObjectMapper objectMapper;

    public PayoutWebhookPayload create(PayoutEvent event) {
        return new PayoutWebhookPayload(
                CURRENT_PAYOUT_PAYLOAD_VERSION,
                event.eventId(),
                event.eventType(),
                PUBLIC_PAYOUT_ID_PREFIX + event.data().getPayoutId(),
                event.data().getMerchantId(),
                event.data().getAmount(),
                event.data().getCurrency(),
                event.data().getStatus(),
                instant(event.occurredAt()),
                event.data().getFailureCode()
        );
    }

    public String render(PayoutWebhookPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize webhook payload for event " + payload.eventId(), e);
        }
    }

    /**
     * occurredAt is UTC without an offset on the internal wire; merchants get an explicit
     * ISO-8601 instant ("...Z") so there is no timezone guesswork on their side.
     */
    private static String instant(java.time.LocalDateTime occurredAt) {
        return occurredAt == null ? null : DateTimeFormatter.ISO_INSTANT.format(occurredAt.toInstant(ZoneOffset.UTC));
    }

    public WebhookPayload create(PaymentEvent event) {
        String occurredAt = instant(event.occurredAt());

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
