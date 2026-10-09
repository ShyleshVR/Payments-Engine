package com.shylesh.payment_service.common.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shylesh.payment_service.event.PaymentCreatedEvent;
import com.shylesh.payment_service.event.Topics;
import com.shylesh.payment_service.saga.LedgerCommandMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Outbox rows for payment events (topic payment-created) and saga commands (ledger-commands).
 * Both are keyed by payment id and share the per-payment ordering of the outbox.
 */
@Component
@RequiredArgsConstructor
public class OutboxEventFactory {

    private static final String AGGREGATE_TYPE = "PAYMENT";

    private final ObjectMapper objectMapper;

    public OutboxEvent createPaymentCreatedEvent(PaymentCreatedEvent event, String traceParent) {
        return createPaymentEvent(event, "PAYMENT_CREATED", traceParent);
    }

    /** eventType: PAYMENT_CREATED, PAYMENT_AUTHORIZED, PAYMENT_COMPLETED, PAYMENT_FAILED, ... */
    public OutboxEvent createPaymentEvent(PaymentCreatedEvent event, String eventType, String traceParent) {
        return build(event.getPaymentId(), Topics.PAYMENT_CREATED, eventType, event, traceParent);
    }

    public OutboxEvent createLedgerCommand(LedgerCommandMessage command, String traceParent) {
        return build(command.paymentId(), Topics.LEDGER_COMMANDS, command.commandType(), command, traceParent);
    }

    private OutboxEvent build(UUID aggregateId, String topic, String eventType, Object payload, String traceParent) {
        try {
            return OutboxEvent.builder()
                    .id(UUID.randomUUID())
                    .aggregateId(aggregateId)
                    .aggregateType(AGGREGATE_TYPE)
                    .topic(topic)
                    .eventType(eventType)
                    .payload(objectMapper.writeValueAsString(payload))
                    .status(OutboxEventStatus.PENDING)
                    .traceParent(traceParent)
                    .createdAt(LocalDateTime.now())
                    .build();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize " + eventType, e);
        }
    }
}
