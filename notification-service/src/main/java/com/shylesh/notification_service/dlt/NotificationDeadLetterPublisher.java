package com.shylesh.notification_service.dlt;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shylesh.notification_service.persistance.Notification;

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
 * Sends one dead letter and waits for the broker ack. Throws if the send fails, so the
 * caller (NotificationDeadLetterRelay) leaves the notification unmarked and retries it.
 * SEND_TIMEOUT_SECONDS must exceed the producer's delivery.timeout.ms, so a failed send has
 * really been dropped by the producer rather than still buffered for late delivery. Delivery
 * is still at-least-once (an ack lost after the broker wrote the record means a resend), so
 * DLT consumers should deduplicate on notificationId.
 */
@Component
@RequiredArgsConstructor
public class NotificationDeadLetterPublisher {

    static final long SEND_TIMEOUT_SECONDS = 15;

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public void publish(Notification notification) {
        NotificationDeadLetterEvent event = new NotificationDeadLetterEvent(
                notification.getEventId(),
                notification.getId(),
                notification.getPaymentId(),
                notification.getCustomerId(),
                notification.getEventType(),
                notification.getChannel(),
                notification.getAttemptCount(),
                notification.getLastError(),
                notification.getUpdatedAt() != null ? notification.getUpdatedAt() : LocalDateTime.now()
        );

        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new DeadLetterPublishException("Failed to serialize dead letter for notification " + notification.getId(), e);
        }

        try {
            kafkaTemplate.send(
                    NotificationTopics.NOTIFICATION_DEAD_LETTER,
                    notification.getPaymentId().toString(),
                    payload
            ).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DeadLetterPublishException("Interrupted publishing dead letter for notification " + notification.getId(), e);
        } catch (ExecutionException | TimeoutException e) {
            throw new DeadLetterPublishException("Failed to publish dead letter for notification " + notification.getId(), e);
        }

        Counter.builder("notifications.dlt")
                .tag("channel", notification.getChannel().name())
                .tag("eventType", notification.getEventType())
                .register(meterRegistry)
                .increment();
    }
}
