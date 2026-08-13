package com.shylesh.notification_service.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shylesh.notification_service.event.PaymentCreatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentCreatedConsumer {

    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = "payment-created",
            groupId = "notification-service"
    )
    public void consume(String message) {

        try {
            PaymentCreatedEvent event =
                    objectMapper.readValue(
                            message,
                            PaymentCreatedEvent.class
                    );

            log.info(
                    "Received payment-created event. paymentId={}, amount={}, currency={}",
                    event.getPaymentId(),
                    event.getAmount(),
                    event.getCurrency()
            );

        } catch (Exception e) {

            log.error(
                    "Failed to process payment-created event: {}",
                    message,
                    e
            );

            throw new IllegalStateException(
                    "Failed to process payment-created event",
                    e
            );
        }
    }
}