package com.shylesh.webhook_service.consumer;

import com.shylesh.webhook_service.event.PaymentEvent;
import com.shylesh.webhook_service.event.PaymentEventParser;
import com.shylesh.webhook_service.service.WebhookEventService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Parse/validation failures throw InvalidEventException (straight to the DLT); everything
 * thrown by handle() is classified by KafkaConsumerConfig's error handler.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentEventConsumer {

    private final PaymentEventParser parser;
    private final WebhookEventService webhookEventService;

    @KafkaListener(topics = "payment-created", groupId = "webhook-service")
    public void consume(String message) {
        PaymentEvent event = parser.parse(message);

        log.info("Received payment event. eventId={}, eventType={}", event.eventId(), event.eventType());

        webhookEventService.handle(event);
    }
}
