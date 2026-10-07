package com.shylesh.webhook_service.dlt;

import com.shylesh.webhook_service.persistence.WebhookDelivery;
import com.shylesh.webhook_service.persistence.WebhookDeliveryRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Outbox-style relay for dead letters: publishes FAILED deliveries not yet dead-lettered and
 * marks each only after Kafka acknowledges it. So a dead letter is never sent for a FAILED
 * status that rolled back, and never lost while Kafka is down. SKIP LOCKED keeps concurrent
 * instances off the same row.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WebhookDeadLetterRelay {

    private static final int BATCH_SIZE = 100;

    private final WebhookDeliveryRepository deliveryRepository;
    private final WebhookDeadLetterPublisher deadLetterPublisher;
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
                log.error("Failed to relay webhook dead letter, will retry: {}", e.getMessage(), e);
                return;
            }
        }
    }

    boolean relayNext() {
        Optional<WebhookDelivery> next = deliveryRepository.lockNextUnpublishedDeadLetter();
        if (next.isEmpty()) {
            return false;
        }

        WebhookDelivery delivery = next.get();
        deadLetterPublisher.publish(delivery);
        delivery.markDeadLetterPublished(LocalDateTime.now());

        log.info("Webhook dead letter published. deliveryId={}, eventId={}, attempts={}",
                delivery.getId(), delivery.getEventId(), delivery.getAttemptCount());
        return true;
    }
}
