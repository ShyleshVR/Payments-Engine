package com.shylesh.webhook_service.consumer;

import com.shylesh.webhook_service.event.PayoutEvent;
import com.shylesh.webhook_service.event.PayoutEventParser;
import com.shylesh.webhook_service.service.WebhookEventService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Payout events from payout-service; same error handling as payment events (KafkaConsumerConfig). */
@Component
@RequiredArgsConstructor
@Slf4j
public class PayoutEventConsumer {

    private final PayoutEventParser parser;
    private final WebhookEventService webhookEventService;

    @KafkaListener(topics = "payout-events", groupId = "webhook-service")
    public void consume(String message) {
        PayoutEvent event = parser.parse(message);

        log.info("Received payout event. eventId={}, eventType={}", event.eventId(), event.eventType());

        webhookEventService.handle(event);
    }
}
