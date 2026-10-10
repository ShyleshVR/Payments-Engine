package com.shylesh.payout_service.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import com.shylesh.payout_service.tracing.TraceContext;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.SerializationException;
import org.springframework.kafka.core.KafkaTemplate;
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
 * Relays outbox rows to Kafka in batches, like payment-service's OutboxPublisher: a transaction
 * claims up to batchSize publishable rows (FOR UPDATE SKIP LOCKED, so every replica can relay),
 * sends them all, waits for the acks and marks each by its own outcome, going on at once while
 * batches are full. Per-payout order is kept (a batch holds at most one message of a payout),
 * failed sends back off exponentially, and a message that can never be sent is parked as FAILED.
 * At-least-once: the ledger and consumers ignore duplicates.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxRelay {

    /** What one batch came to. */
    record Batch(int claimed, int published, int failed) {
    }

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final TransactionTemplate transactionTemplate;
    private final OutboxProperties properties;
    private final MeterRegistry meterRegistry;
    private final TraceContext traceContext;

    @Scheduled(fixedDelayString = "${outbox.publisher.poll-interval:PT1S}")
    public void publishPending() {
        int published = 0;
        while (true) {
            Batch batch = transactionTemplate.execute(status -> publishBatch());
            if (batch == null) {
                break;
            }
            published += batch.published();
            // a failure: the broker is likely unhealthy and the backoff paces the retry;
            // a short batch: everything due has been sent
            if (batch.failed() > 0 || batch.claimed() < properties.publisher().batchSize()) {
                break;
            }
        }
        if (published > 0) {
            log.debug("Published {} outbox message(s)", published);
        }
    }

    Batch publishBatch() {
        List<OutboxEvent> claimed = outboxEventRepository.claimPublishable(LocalDateTime.now(),
                properties.publisher().batchSize());
        if (claimed.isEmpty()) {
            return new Batch(0, 0, 0);
        }
        // send everything first, then wait: the producer batches and pipelines the records
        List<CompletableFuture<?>> sends = new ArrayList<>(claimed.size());
        for (OutboxEvent event : claimed) {
            try {
                sends.add(traceContext.continueTrace(event.getTraceParent(), "outbox publish " + event.getEventType(),
                        () -> kafkaTemplate.send(event.getTopic(), event.getAggregateId().toString(), event.getPayload())));
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
            LocalDateTime publishedAt = LocalDateTime.now();
            event.markPublished(publishedAt);
            Timer.builder("outbox.publish.lag")
                    .register(meterRegistry)
                    .record(Duration.between(event.getCreatedAt(), publishedAt));
            published++;
        }
        return new Batch(claimed.size(), published, failed);
    }

    private void recordFailure(OutboxEvent event, Exception e) {
        String error = describe(e);
        int attempt = event.getAttemptCount() + 1;
        if (isPermanent(e)) {
            event.markFailed(error);
            countFailure("permanent");
            log.error("Outbox message {} ({}, payout {}) can never be published and was parked as FAILED. error={}",
                    event.getId(), event.getEventType(), event.getAggregateId(), error);
            return;
        }
        LocalDateTime nextAttemptAt = LocalDateTime.now().plus(backoff(attempt));
        event.scheduleRetry(nextAttemptAt, error);
        countFailure("transient");
        log.warn("Failed to publish outbox message {} (attempt {}), retrying at {}. error={}",
                event.getId(), attempt, nextAttemptAt, error);
    }

    Duration backoff(int attempt) {
        Duration delay = properties.publisher().initialBackoff().multipliedBy(1L << Math.min(attempt - 1, 20));
        return delay.compareTo(properties.publisher().maxBackoff()) > 0 ? properties.publisher().maxBackoff() : delay;
    }

    private static boolean isPermanent(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SerializationException || t instanceof RecordTooLargeException) {
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
        Counter.builder("outbox.publish.failures").tag("kind", kind).register(meterRegistry).increment();
    }
}
