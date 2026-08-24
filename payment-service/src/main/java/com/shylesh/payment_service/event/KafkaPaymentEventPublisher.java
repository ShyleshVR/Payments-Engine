package com.shylesh.payment_service.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.shylesh.payment_service.common.outbox.OutboxEvent;

import lombok.RequiredArgsConstructor;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

@Service
@RequiredArgsConstructor
public class KafkaPaymentEventPublisher implements PaymentEventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public CompletableFuture<SendResult<String, Object>> publish(OutboxEvent outboxEvent) {

        try {
            JsonNode payload =
                    objectMapper.readTree(outboxEvent.getPayload());

            EventEnvelope<JsonNode> envelope =
                    new EventEnvelope<>(
                            outboxEvent.getId(),
                            outboxEvent.getEventType(),
                            outboxEvent.getCreatedAt(),
                            payload
                    );

            return kafkaTemplate.send(
                    Topics.PAYMENT_CREATED,
                    outboxEvent.getAggregateId().toString(),
                    envelope
            );

        } catch (JsonProcessingException e) {

            throw new IllegalStateException(
                    "Failed to create event envelope for outbox event "
                            + outboxEvent.getId(),
                    e
            );
        }
    }
}