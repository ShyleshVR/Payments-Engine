package com.shylesh.webhook_service.dlt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shylesh.webhook_service.persistence.WebhookDelivery;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Publishes one dead letter and waits for Kafka to acknowledge it. Never called inside a
 * database transaction — WebhookDeadLetterRelay calls it for FAILED deliveries that are
 * already committed, so a Kafka outage cannot roll back delivery state.
 */
@Component
public class WebhookDeadLetterPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final Duration sendTimeout;

    public WebhookDeadLetterPublisher(
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            @Value("${webhook.dlt.send-timeout:10s}") Duration sendTimeout) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.sendTimeout = sendTimeout;
    }

    public void publish(WebhookDelivery delivery) throws DeadLetterPublishException {
        WebhookDeadLetterEvent event = new WebhookDeadLetterEvent(
                delivery.getEventId(),
                delivery.getId(),
                delivery.getPaymentId(),
                delivery.getMerchantId(),
                delivery.getEventType(),
                delivery.getUrl(),
                delivery.getAttemptCount(),
                delivery.getLastError(),
                delivery.getUpdatedAt()
        );

        try {
            String payload = objectMapper.writeValueAsString(event);
            kafkaTemplate.send(
                    WebhookTopics.WEBHOOK_DEAD_LETTER,
                    delivery.getPaymentId().toString(),
                    payload
            ).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DeadLetterPublishException("Interrupted while publishing dead letter for delivery " + delivery.getId(), e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new DeadLetterPublishException(
                    "Kafka rejected dead letter for delivery " + delivery.getId() + ": " + cause.getMessage(), cause);
        } catch (Exception e) {
            // TimeoutException, serialization failures, and producer errors thrown synchronously by send().
            throw new DeadLetterPublishException(
                    "Failed to publish dead letter for delivery " + delivery.getId() + ": " + e.getMessage(), e);
        }
    }
}
