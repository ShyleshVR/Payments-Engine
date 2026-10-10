package com.shylesh.webhook_service.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Parses and validates a payout event before any database work, like PaymentEventParser: a
 * malformed message fails fast with InvalidEventException (dead-lettered immediately).
 */
@Component
@RequiredArgsConstructor
public class PayoutEventParser {

    private final ObjectMapper objectMapper;

    /** Wire shape {eventId, eventType, occurredAt, data}, with typed data (exact amounts). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    static class Envelope {
        private UUID eventId;
        private String eventType;
        private LocalDateTime occurredAt;
        private PayoutEventData data;
    }

    public PayoutEvent parse(String message) {
        Envelope envelope;
        try {
            envelope = objectMapper.readValue(message, Envelope.class);
        } catch (Exception e) {
            throw new InvalidEventException("Malformed payout event: " + e.getMessage(), e);
        }

        PayoutEventData data = envelope.getData();
        List<String> missing = new ArrayList<>();
        if (envelope.getEventId() == null) missing.add("eventId");
        if (envelope.getEventType() == null || envelope.getEventType().isBlank()) missing.add("eventType");
        if (data == null) {
            missing.add("data");
        } else {
            if (data.getPayoutId() == null) missing.add("data.payoutId");
            if (data.getMerchantId() == null) missing.add("data.merchantId");
            if (data.getAmount() == null) missing.add("data.amount");
            if (data.getCurrency() == null) missing.add("data.currency");
        }

        if (!missing.isEmpty()) {
            throw new InvalidEventException("Payout event missing required fields " + missing + ". eventId=" + envelope.getEventId());
        }
        return new PayoutEvent(envelope.getEventId(), envelope.getEventType(), envelope.getOccurredAt(), data);
    }
}
