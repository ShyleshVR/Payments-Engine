package com.shylesh.ledger_service.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shylesh.ledger_service.event.EventEnvelope;
import com.shylesh.ledger_service.service.LedgerPostingService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentEventConsumer {

    private final ObjectMapper objectMapper;
    private final LedgerPostingService ledgerPostingService;

    @KafkaListener(topics = "payment-created")
    public void consume(String message) {

        EventEnvelope envelope;

        try {
            envelope = objectMapper.readValue(message, EventEnvelope.class);
        } catch (Exception e) {
            log.error("Failed to deserialize payment event envelope: {}", message, e);
            throw new IllegalStateException("Failed to deserialize payment event envelope", e);
        }

        log.info(
                "Received payment event. eventId={}, eventType={}",
                envelope.getEventId(),
                envelope.getEventType()
        );

        ledgerPostingService.handle(envelope);
    }
}
