package com.shylesh.webhook_service.dlt;

import com.shylesh.webhook_service.persistence.WebhookDelivery;
import com.shylesh.webhook_service.persistence.WebhookDeliveryRepository;
import com.shylesh.webhook_service.persistence.WebhookDeliveryStatus;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Moves committed FAILED deliveries onto webhook-deliveries.DLT — the same "write the intent
 * to the DB, publish separately, mark published only after Kafka confirms" approach as
 * payment-service's OutboxPublisher.
 *
 * Guarantees: a dead letter is never lost (it stays pending in the DB until Kafka acks),
 * and is delivered at least once — if Kafka acks but stamping dlt_published_at fails, the
 * next run publishes it again, so DLT consumers should dedupe on deliveryId.
 *
 * Single-instance assumption, same as WebhookDispatcher.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WebhookDeadLetterRelay {

    private final WebhookDeliveryRepository deliveryRepository;
    private final WebhookDeadLetterPublisher deadLetterPublisher;
    private final MeterRegistry meterRegistry;

    @Scheduled(fixedDelay = 5000)
    public void relayPendingDeadLetters() {

        List<WebhookDelivery> pending = deliveryRepository
                .findTop100ByStatusAndDltPublishedAtIsNullOrderByUpdatedAtAsc(WebhookDeliveryStatus.FAILED);

        for (WebhookDelivery delivery : pending) {
            try {
                deadLetterPublisher.publish(delivery);
            } catch (DeadLetterPublishException e) {
                Counter.builder("webhooks.dlt.publish.failed")
                        .register(meterRegistry)
                        .increment();

                log.error(
                        "Dead letter not confirmed by Kafka; will retry next run. deliveryId={}, eventId={}, pendingInBatch={}, error={}",
                        delivery.getId(),
                        delivery.getEventId(),
                        pending.size(),
                        e.getMessage(),
                        e
                );
                // Kafka is most likely unavailable: stop here instead of waiting out a send
                // timeout for every remaining row. They stay pending for the next run.
                return;
            }

            deliveryRepository.markDeadLetterPublished(delivery.getId(), LocalDateTime.now());

            Counter.builder("webhooks.dlt")
                    .tag("eventType", delivery.getEventType())
                    .register(meterRegistry)
                    .increment();

            log.info(
                    "Dead letter published. deliveryId={}, eventId={}, merchantId={}",
                    delivery.getId(),
                    delivery.getEventId(),
                    delivery.getMerchantId()
            );
        }
    }
}
