package com.shylesh.payment_service.common.outbox;

import com.shylesh.payment_service.event.EventSerializationException;
import com.shylesh.payment_service.event.PaymentEventPublisher;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OutboxPublisherTest {

    private OutboxEventRepository repository;
    private PaymentEventPublisher eventPublisher;
    private OutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        repository = mock(OutboxEventRepository.class);
        eventPublisher = mock(PaymentEventPublisher.class);

        OutboxProperties properties = new OutboxProperties(
                new OutboxProperties.Publisher(100, Duration.ofSeconds(15), Duration.ofSeconds(1), Duration.ofMinutes(5)),
                new OutboxProperties.Cleanup(Duration.ofDays(7))
        );

        publisher = new OutboxPublisher(
                repository,
                eventPublisher,
                new TransactionTemplate(mock(PlatformTransactionManager.class)),
                properties,
                new SimpleMeterRegistry()
        );
    }

    private OutboxEvent pendingEvent() {
        return OutboxEvent.builder()
                .id(UUID.randomUUID())
                .aggregateId(UUID.randomUUID())
                .aggregateType("PAYMENT")
                .eventType("PAYMENT_CREATED")
                .payload("{}")
                .status(OutboxEventStatus.PENDING)
                .createdAt(LocalDateTime.now())
                .build();
    }

    @Test
    void publishesEachClaimedEventAndMarksItPublished() {
        OutboxEvent first = pendingEvent();
        OutboxEvent second = pendingEvent();
        when(repository.claimNextPublishable(any()))
                .thenReturn(Optional.of(first))
                .thenReturn(Optional.of(second))
                .thenReturn(Optional.empty());
        when(eventPublisher.publish(any())).thenReturn(CompletableFuture.completedFuture(null));

        publisher.publishPendingEvents();

        assertThat(first.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
        assertThat(second.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
        assertThat(first.getAttemptCount()).isEqualTo(1);
        assertThat(first.getPublishedAt()).isNotNull();
    }

    @Test
    void transientFailureSchedulesBackoffRecordsErrorAndStopsTheBatch() {
        OutboxEvent event = pendingEvent();
        when(repository.claimNextPublishable(any())).thenReturn(Optional.of(event));
        when(eventPublisher.publish(any()))
                .thenReturn(CompletableFuture.failedFuture(new org.apache.kafka.common.errors.TimeoutException("broker down")));

        LocalDateTime before = LocalDateTime.now();
        publisher.publishPendingEvents();

        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(event.getAttemptCount()).isEqualTo(1);
        assertThat(event.getNextAttemptAt()).isAfter(before);
        assertThat(event.getLastError()).contains("broker down");
        verify(repository, times(1)).claimNextPublishable(any());
    }

    @Test
    void eventThatCanNeverBeSerializedIsParkedAsFailed() {
        OutboxEvent event = pendingEvent();
        when(repository.claimNextPublishable(any())).thenReturn(Optional.of(event));
        when(eventPublisher.publish(any()))
                .thenThrow(new EventSerializationException("bad payload", new RuntimeException("unparseable")));

        publisher.publishPendingEvents();

        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.FAILED);
        assertThat(event.getAttemptCount()).isEqualTo(1);
        assertThat(event.getNextAttemptAt()).isNull();
    }

    @Test
    void backoffDoublesPerAttemptAndIsCapped() {
        assertThat(publisher.backoff(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(publisher.backoff(2)).isEqualTo(Duration.ofSeconds(2));
        assertThat(publisher.backoff(4)).isEqualTo(Duration.ofSeconds(8));
        assertThat(publisher.backoff(30)).isEqualTo(Duration.ofMinutes(5));
    }
}
