package com.shylesh.payment_service.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;

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
            // Amounts must reach consumers exactly as stored. By default readTree turns decimals
            // into doubles (large amounts can lose cents) and strips trailing zeros (75.50 -> 75.5).
            JsonNode payload = objectMapper.reader()
                    .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .without(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
                    .readTree(outboxEvent.getPayload());

            EventEnvelope<JsonNode> envelope =
                    new EventEnvelope<>(
                            outboxEvent.getId(),
                            outboxEvent.getEventType(),
                            outboxEvent.getCreatedAt(),
                            payload
                    );

            return kafkaTemplate.send(
                    outboxEvent.getTopic(),
                    outboxEvent.getAggregateId().toString(),
                    envelope
            );

        } catch (JsonProcessingException e) {

            throw new EventSerializationException(
                    "Failed to create event envelope for outbox event "
                            + outboxEvent.getId(),
                    e
            );
        }
    }
}