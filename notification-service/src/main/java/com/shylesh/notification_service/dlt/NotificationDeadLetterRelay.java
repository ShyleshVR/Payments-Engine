package com.shylesh.notification_service.dlt;

import com.shylesh.notification_service.persistance.Notification;
import com.shylesh.notification_service.persistance.NotificationRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Outbox-style relay for dead letters: publishes FAILED notifications that haven't been
 * dead-lettered yet, and marks each one only after Kafka acknowledges it. A dead letter is
 * therefore never sent for a FAILED status that rolled back, and never lost if Kafka is down
 * (it's retried on the next poll). SKIP LOCKED keeps concurrent instances off the same row.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationDeadLetterRelay {

    private static final int BATCH_SIZE = 100;

    private final NotificationRepository notificationRepository;
    private final NotificationDeadLetterPublisher deadLetterPublisher;
    private final TransactionTemplate transactionTemplate;

    @Scheduled(fixedDelay = 5000)
    public void relayDeadLetters() {
        for (int i = 0; i < BATCH_SIZE; i++) {
            try {
                Boolean relayed = transactionTemplate.execute(status -> relayNext());
                if (!Boolean.TRUE.equals(relayed)) {
                    return;
                }
            } catch (Exception e) {
                // Leave the row unmarked; it is retried on the next poll.
                log.error("Failed to relay dead letter, will retry: {}", e.getMessage(), e);
                return;
            }
        }
    }

    boolean relayNext() {
        Optional<Notification> next = notificationRepository.lockNextUnpublishedDeadLetter();
        if (next.isEmpty()) {
            return false;
        }

        Notification notification = next.get();
        deadLetterPublisher.publish(notification);
        notification.markDeadLetterPublished(LocalDateTime.now());

        log.info(
                "Dead letter published. notificationId={}, eventId={}, attempts={}",
                notification.getId(),
                notification.getEventId(),
                notification.getAttemptCount()
        );
        return true;
    }
}
