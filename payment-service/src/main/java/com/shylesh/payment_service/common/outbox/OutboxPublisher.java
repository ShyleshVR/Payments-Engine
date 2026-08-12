package com.shylesh.payment_service.common.outbox;

import com.shylesh.payment_service.event.PaymentEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxPublisher {

    private final OutboxEventRepository outboxEventRepository;
    private final PaymentEventPublisher paymentEventPublisher;

    @Scheduled(fixedDelay = 5000)
    @Transactional
    public void publishPendingEvents() {
        log.info("Publishing pending outbox events...");
        List<OutboxEvent> events =
                outboxEventRepository
                        .findTop100ByStatusOrderByCreatedAtAsc(
                                OutboxEventStatus.PENDING
                        );

        for (OutboxEvent event : events) {

            try {
                paymentEventPublisher.publish(event).get();
                event.markPublished(LocalDateTime.now());
            } catch (Exception e) {
                log.error(
                        "Failed to publish outbox event with ID {}: {}",
                        event.getId(),
                        e.getMessage()
                );
            }
        }
        outboxEventRepository.saveAll(events);
    }
}