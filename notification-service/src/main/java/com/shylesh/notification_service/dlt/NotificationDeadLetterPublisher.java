package com.shylesh.notification_service.dlt;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shylesh.notification_service.persistance.Notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationDeadLetterPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public void publish(Notification notification, String lastError) {
        NotificationDeadLetterEvent event = new NotificationDeadLetterEvent(
                notification.getEventId(),
                notification.getId(),
                notification.getPaymentId(),
                notification.getCustomerId(),
                notification.getEventType(),
                notification.getChannel(),
                notification.getAttemptCount(),
                lastError,
                LocalDateTime.now()
        );

        try {
            String payload = objectMapper.writeValueAsString(event);
            kafkaTemplate.send(
                    NotificationTopics.NOTIFICATION_DEAD_LETTER,
                    notification.getPaymentId().toString(),
                    payload
            );
        } catch (JsonProcessingException e) {
            log.error(
                    "Failed to serialize dead letter event for notification {}: {}",
                    notification.getId(),
                    e.getMessage(),
                    e
            );
        }
    }
}
