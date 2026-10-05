package com.shylesh.payment_service.common.outbox;

import com.shylesh.payment_service.event.EventSerializationException;
import com.shylesh.payment_service.event.PaymentEventPublisher;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.SerializationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Relays outbox rows to Kafka. Each event is claimed, sent and marked in its own short
 * transaction (row lock held for at most one send, bounded by sendTimeout), so:
 * - several instances can run concurrently (claims use FOR UPDATE SKIP LOCKED);
 * - a payment's events are published in commit order (see claimNextPublishable);
 * - a failed send is retried with exponential backoff and its attempts are recorded;
 * - an event that can never be published is parked as FAILED instead of retried forever.
 * Delivery is at-least-once: a send that times out may still have reached the broker, and
 * consumers deduplicate on eventId.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxPublisher {

    enum Outcome { NOTHING_DUE, PUBLISHED, FAILED }

    private final OutboxEventRepository outboxEventRepository;
    private final PaymentEventPublisher paymentEventPublisher;
    private final TransactionTemplate transactionTemplate;
    private final OutboxProperties properties;
    private final MeterRegistry meterRegistry;

    @Scheduled(fixedDelayString = "${outbox.publisher.poll-interval:PT5S}")
    public void publishPendingEvents() {
        int published = 0;

        for (int i = 0; i < properties.publisher().batchSize(); i++) {
            Outcome outcome = transactionTemplate.execute(status -> publishNext());

            // On a failure, stop: the broker is likely unhealthy, and the failed event's backoff
            // paces the next attempt instead of blocking this poll on every remaining event.
            if (outcome != Outcome.PUBLISHED) {
                break;
            }
            published++;
        }

        if (published > 0) {
            log.info("Published {} outbox event(s)", published);
        }
    }

    Outcome publishNext() {
        Optional<OutboxEvent> claimed = outboxEventRepository.claimNextPublishable(LocalDateTime.now());
        if (claimed.isEmpty()) {
            return Outcome.NOTHING_DUE;
        }

        OutboxEvent event = claimed.get();

        try {
            paymentEventPublisher.publish(event)
                    .get(properties.publisher().sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            recordFailure(event, e);
            return Outcome.FAILED;
        } catch (Exception e) {
            recordFailure(event, e);
            return Outcome.FAILED;
        }

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

        return Outcome.PUBLISHED;
    }

    private void recordFailure(OutboxEvent event, Exception e) {
        String error = describe(e);
        int attempt = event.getAttemptCount() + 1;

        if (isPermanent(e)) {
            event.markFailed(error);
            countFailure("permanent");
            log.error(
                    "Outbox event {} ({}, aggregate {}) can never be published and was parked as FAILED. "
                            + "Later events for this aggregate are held until it is resolved. error={}",
                    event.getId(), event.getEventType(), event.getAggregateId(), error
            );
            return;
        }

        LocalDateTime nextAttemptAt = LocalDateTime.now().plus(backoff(attempt));
        event.scheduleRetry(nextAttemptAt, error);
        countFailure("transient");
        log.warn(
                "Failed to publish outbox event {} (attempt {}), retrying at {}. error={}",
                event.getId(), attempt, nextAttemptAt, error
        );
    }

    Duration backoff(int attempt) {
        Duration initial = properties.publisher().initialBackoff();
        Duration max = properties.publisher().maxBackoff();
        Duration delay = initial.multipliedBy(1L << Math.min(attempt - 1, 20));
        return delay.compareTo(max) > 0 ? max : delay;
    }

    private static boolean isPermanent(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof EventSerializationException
                    || t instanceof SerializationException
                    || t instanceof RecordTooLargeException) {
                return true;
            }
        }
        return false;
    }

    private static String describe(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }

    private void countFailure(String kind) {
        Counter.builder("outbox.publish.failures")
                .tag("kind", kind)
                .register(meterRegistry)
                .increment();
    }
}
