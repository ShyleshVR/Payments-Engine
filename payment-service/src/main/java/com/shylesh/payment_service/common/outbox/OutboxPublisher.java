package com.shylesh.payment_service.common.outbox;

import com.shylesh.payment_service.event.PaymentEventPublisher;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxPublisher {

    private final OutboxEventRepository outboxEventRepository;
    private final PaymentEventPublisher paymentEventPublisher;
    private final MeterRegistry meterRegistry;

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
                LocalDateTime publishedAt = LocalDateTime.now();
                event.markPublished(publishedAt);

                Timer.builder("outbox.publish.lag")
                        .serviceLevelObjectives(
                                Duration.ofMillis(100),
                                Duration.ofSeconds(1),
                                Duration.ofSeconds(5),
                                Duration.ofSeconds(10),
                                Duration.ofSeconds(30),
                                Duration.ofMinutes(1)
                        )
                        .register(meterRegistry)
                        .record(Duration.between(event.getCreatedAt(), publishedAt));
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