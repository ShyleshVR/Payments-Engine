package com.shylesh.notification_service.consumer;

import com.shylesh.notification_service.event.EventEnvelope;
import com.shylesh.notification_service.event.PaymentEventParser;
import com.shylesh.notification_service.service.NotificationEventService;

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
    private final NotificationEventService notificationEventService;

    @KafkaListener(
            topics = "payment-created",
            groupId = "notification-service"
    )
    public void consume(String message) {
        EventEnvelope envelope = parser.parse(message);

        log.info(
                "Received payment event. eventId={}, eventType={}",
                envelope.getEventId(),
                envelope.getEventType()
        );

        notificationEventService.handle(envelope);
    }
}
