package com.shylesh.payment_service.common.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shylesh.payment_service.event.PaymentCreatedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class OutboxEventFactory {

    private static final String AGGREGATE_TYPE = "PAYMENT";
    private static final String EVENT_TYPE = "PAYMENT_CREATED";

    private final ObjectMapper objectMapper;

    public OutboxEvent createPaymentCreatedEvent(
            PaymentCreatedEvent event
    ) {
        try {
            return OutboxEvent.builder()
                    .id(UUID.randomUUID())
                    .aggregateId(event.getPaymentId())
                    .aggregateType(AGGREGATE_TYPE)
                    .eventType(EVENT_TYPE)
                    .payload(objectMapper.writeValueAsString(event))
                    .status(OutboxEventStatus.PENDING)
                    .createdAt(LocalDateTime.now())
                    .build();

        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Failed to serialize payment created event", e
            );
        }
    }
}