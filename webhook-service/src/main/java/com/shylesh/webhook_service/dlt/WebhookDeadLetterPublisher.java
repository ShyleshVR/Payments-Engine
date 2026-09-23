package com.shylesh.webhook_service.dlt;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shylesh.webhook_service.persistence.WebhookDelivery;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
@Slf4j
public class WebhookDeadLetterPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public void publish(WebhookDelivery delivery, String lastError) {
        WebhookDeadLetterEvent event = new WebhookDeadLetterEvent(
                delivery.getEventId(),
                delivery.getId(),
                delivery.getPaymentId(),
                delivery.getMerchantId(),
                delivery.getEventType(),
                delivery.getUrl(),
                delivery.getAttemptCount(),
                lastError,
                LocalDateTime.now()
        );

        try {
            String payload = objectMapper.writeValueAsString(event);
            kafkaTemplate.send(
                    WebhookTopics.WEBHOOK_DEAD_LETTER,
                    delivery.getPaymentId().toString(),
                    payload
            );

            Counter.builder("webhooks.dlt")
                    .tag("eventType", delivery.getEventType())
                    .register(meterRegistry)
                    .increment();
        } catch (JsonProcessingException e) {
            log.error(
                    "Failed to serialize dead letter event for webhook delivery {}: {}",
                    delivery.getId(),
                    e.getMessage(),
                    e
            );
        }
    }
}
