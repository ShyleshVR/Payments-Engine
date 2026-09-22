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

    private final ObjectMapper objectMapper;

    public OutboxEvent createPaymentCreatedEvent(PaymentCreatedEvent event) {
        return build(event, "PAYMENT_CREATED");
    }

    public OutboxEvent createPaymentCompletedEvent(PaymentCreatedEvent event) {
        return build(event, "PAYMENT_COMPLETED");
    }

    public OutboxEvent createPaymentRefundedEvent(PaymentCreatedEvent event) {
        return build(event, "PAYMENT_REFUNDED");
    }

    private OutboxEvent build(PaymentCreatedEvent event, String eventType) {
        try {
            return OutboxEvent.builder()
                    .id(UUID.randomUUID())
                    .aggregateId(event.getPaymentId())
                    .aggregateType(AGGREGATE_TYPE)
                    .eventType(eventType)
                    .payload(objectMapper.writeValueAsString(event))
                    .status(OutboxEventStatus.PENDING)
                    .createdAt(LocalDateTime.now())
                    .build();

        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Failed to serialize " + eventType + " event", e
            );
        }
    }
}