package com.shylesh.webhook_service.event;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses and validates a raw payment event before any database work, so a malformed message
 * fails fast with InvalidEventException (dead-lettered immediately) instead of failing deep
 * inside JPA with an exception that looks retryable.
 */
@Component
@RequiredArgsConstructor
public class PaymentEventParser {

    private final ObjectMapper objectMapper;

    public PaymentEvent parse(String message) {
        EventEnvelope envelope;
        try {
            envelope = objectMapper.readValue(message, EventEnvelope.class);
        } catch (Exception e) {
            throw new InvalidEventException("Malformed payment event: " + e.getMessage(), e);
        }

        PaymentEventData data = envelope.getData();
        List<String> missing = new ArrayList<>();
        if (envelope.getEventId() == null) missing.add("eventId");
        if (envelope.getEventType() == null || envelope.getEventType().isBlank()) missing.add("eventType");
        if (data == null) {
            missing.add("data");
        } else {
            if (data.getPaymentId() == null) missing.add("data.paymentId");
            if (data.getMerchantId() == null) missing.add("data.merchantId");
            if (data.getAmount() == null) missing.add("data.amount");
            if (data.getCurrency() == null) missing.add("data.currency");
        }

        if (!missing.isEmpty()) {
            throw new InvalidEventException(
                    "Payment event missing required fields " + missing + ". eventId=" + envelope.getEventId());
        }

        return new PaymentEvent(envelope.getEventId(), envelope.getEventType(), envelope.getOccurredAt(), data);
    }
}
