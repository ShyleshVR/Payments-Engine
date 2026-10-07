package com.shylesh.notification_service.event;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses and validates a raw payment event before any database work. A malformed message, or
 * one missing a field this service relies on, fails with InvalidEventException and is
 * dead-lettered immediately, instead of failing deep inside JPA with an exception that looks
 * retryable (e.g. existsById(null)). customerId stays optional: payments without a customer
 * are valid and simply produce no notification.
 */
@Component
@RequiredArgsConstructor
public class PaymentEventParser {

    private final ObjectMapper objectMapper;

    public EventEnvelope parse(String message) {
        EventEnvelope envelope;
        try {
            envelope = objectMapper.readValue(message, EventEnvelope.class);
        } catch (Exception e) {
            throw new InvalidEventException("Malformed payment event: " + e.getMessage(), e);
        }

        List<String> missing = new ArrayList<>();
        if (envelope.getEventId() == null) missing.add("eventId");
        if (envelope.getEventType() == null || envelope.getEventType().isBlank()) missing.add("eventType");
        if (envelope.getData() == null) {
            missing.add("data");
        } else if (envelope.getData().getPaymentId() == null) {
            missing.add("data.paymentId");
        }

        if (!missing.isEmpty()) {
            throw new InvalidEventException(
                    "Payment event missing required fields " + missing + ". eventId=" + envelope.getEventId());
        }
        return envelope;
    }
}
