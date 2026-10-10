package com.shylesh.payment_service.common.outbox;

import com.shylesh.payment_service.event.EventSerializationException;
import com.shylesh.payment_service.event.PaymentEventPublisher;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import com.shylesh.payment_service.common.tracing.TraceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.SerializationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Relays outbox rows to Kafka in batches: a transaction claims up to batchSize publishable rows,
 * sends them all (the producer pipelines them), waits for the acks (bounded by sendTimeout) and
 * marks each row by its own outcome. While batches come back full it goes on at once. So:
 * - several instances can run concurrently (claims use FOR UPDATE SKIP LOCKED);
 * - a payment's events are published in commit order (see claimPublishable);
 * - a failed send is retried with exponential backoff and its attempts are recorded;
 * - an event that can never be published is parked as FAILED instead of retried forever.
 * Delivery is at-least-once: a send that times out may still have reached the broker, and
 * consumers deduplicate on eventId.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxPublisher {

    /** What one batch came to. */
    record Batch(int claimed, int published, int failed) {
    }

    private final OutboxEventRepository outboxEventRepository;
    private final PaymentEventPublisher paymentEventPublisher;
    private final TransactionTemplate transactionTemplate;
    private final OutboxProperties properties;
    private final MeterRegistry meterRegistry;
    private final TraceContext traceContext;

    @Scheduled(fixedDelayString = "${outbox.publisher.poll-interval:PT5S}")
    public void publishPendingEvents() {
        int published = 0;

        while (true) {
            Batch batch = transactionTemplate.execute(status -> publishBatch());
            if (batch == null) {
                break;
            }
            published += batch.published();
            // On a failure, stop: the broker is likely unhealthy, and the failed events' backoff
            // paces the next attempt. A short batch means everything due has been sent.
            if (batch.failed() > 0 || batch.claimed() < properties.publisher().batchSize()) {
                break;
            }
        }

        if (published > 0) {
            log.info("Published {} outbox event(s)", published);
        }
    }

    Batch publishBatch() {
        List<OutboxEvent> claimed = outboxEventRepository.claimPublishable(LocalDateTime.now(),
                properties.publisher().batchSize());
        if (claimed.isEmpty()) {
            return new Batch(0, 0, 0);
        }

        // Send everything first, then wait: the producer batches and pipelines the records.
        List<CompletableFuture<?>> sends = new ArrayList<>(claimed.size());
        for (OutboxEvent event : claimed) {
            try {
                // sent inside the trace of the transaction that wrote the row, so consumers' spans join it
                sends.add(traceContext.continueTrace(event.getTraceParent(), "outbox publish " + event.getEventType(),
                        () -> paymentEventPublisher.publish(event)));
            } catch (RuntimeException e) {
                sends.add(CompletableFuture.failedFuture(e));
            }
        }

        long deadline = System.nanoTime() + properties.publisher().sendTimeout().toNanos();
        int published = 0;
        int failed = 0;
        for (int i = 0; i < claimed.size(); i++) {
            OutboxEvent event = claimed.get(i);
            try {
                sends.get(i).get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                recordFailure(event, e);
                failed++;
                continue;
            } catch (Exception e) {
                recordFailure(event, e);
                failed++;
                continue;
            }
            markPublished(event);
            published++;
        }
        return new Batch(claimed.size(), published, failed);
    }

    private void markPublished(OutboxEvent event) {
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
