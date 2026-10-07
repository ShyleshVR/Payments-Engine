package com.shylesh.webhook_service.dlt;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shylesh.webhook_service.persistence.WebhookDelivery;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Sends one dead letter and waits for the broker ack; throws if it fails so the relay leaves
 * the delivery unmarked and retries. SEND_TIMEOUT_SECONDS exceeds the producer's
 * delivery.timeout.ms, so a send reported as failed was really dropped, not still buffered.
 * Still at-least-once (a lost ack means a resend): DLT consumers should dedupe on deliveryId.
 */
@Component
@RequiredArgsConstructor
public class WebhookDeadLetterPublisher {

    static final long SEND_TIMEOUT_SECONDS = 15;

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public void publish(WebhookDelivery delivery) {
        WebhookDeadLetterEvent event = new WebhookDeadLetterEvent(
                delivery.getEventId(),
                delivery.getId(),
                delivery.getPaymentId(),
                delivery.getMerchantId(),
                delivery.getEventType(),
                delivery.getUrl(),
                delivery.getAttemptCount(),
                delivery.getLastError(),
                delivery.getUpdatedAt() != null ? delivery.getUpdatedAt() : LocalDateTime.now()
        );

        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new DeadLetterPublishException("Failed to serialize dead letter for delivery " + delivery.getId(), e);
        }

        try {
            kafkaTemplate.send(WebhookTopics.WEBHOOK_DEAD_LETTER, delivery.getMerchantId().toString(), payload)
                    .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DeadLetterPublishException("Interrupted publishing dead letter for delivery " + delivery.getId(), e);
        } catch (ExecutionException | TimeoutException e) {
            throw new DeadLetterPublishException("Failed to publish dead letter for delivery " + delivery.getId(), e);
        }

        Counter.builder("webhooks.dlt")
                .tag("eventType", delivery.getEventType())
                .register(meterRegistry)
                .increment();
    }
}
