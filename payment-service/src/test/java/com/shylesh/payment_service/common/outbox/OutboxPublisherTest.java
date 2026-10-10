package com.shylesh.payment_service.common.outbox;

import com.shylesh.payment_service.event.EventSerializationException;
import com.shylesh.payment_service.event.PaymentEventPublisher;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import com.shylesh.payment_service.common.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
                new SimpleMeterRegistry(),
                new TraceContext(new StaticListableBeanFactory().getBeanProvider(Tracer.class),
                        new StaticListableBeanFactory().getBeanProvider(Propagator.class))
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
        when(repository.claimPublishable(any(), anyInt())).thenReturn(List.of(first, second)).thenReturn(List.of());
        when(eventPublisher.publish(any())).thenReturn(CompletableFuture.completedFuture(null));

        publisher.publishPendingEvents();

        assertThat(first.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
        assertThat(second.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
        assertThat(first.getAttemptCount()).isEqualTo(1);
        assertThat(first.getPublishedAt()).isNotNull();
    }

    @Test
    void everyEventOfABatchIsSentBeforeAnyAckIsAwaited() {
        OutboxEvent first = pendingEvent();
        OutboxEvent second = pendingEvent();
        CompletableFuture<org.springframework.kafka.support.SendResult<String, Object>> firstAck = new CompletableFuture<>();
        when(repository.claimPublishable(any(), anyInt())).thenReturn(List.of(first, second)).thenReturn(List.of());
        when(eventPublisher.publish(first)).thenReturn(firstAck);
        // the second send completes the first ack: had the publisher waited for the first ack
        // before sending the second, it would time out instead
        when(eventPublisher.publish(second)).thenAnswer(invocation -> {
            firstAck.complete(null);
            return CompletableFuture.completedFuture(null);
        });

        publisher.publishPendingEvents();

        assertThat(first.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
        assertThat(second.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
    }

    @Test
    void aFailedSendInABatchIsRetriedLaterAndTheOthersArePublished() {
        OutboxEvent ok = pendingEvent();
        OutboxEvent broken = pendingEvent();
        when(repository.claimPublishable(any(), anyInt())).thenReturn(List.of(ok, broken));
        when(eventPublisher.publish(ok)).thenReturn(CompletableFuture.completedFuture(null));
        when(eventPublisher.publish(broken))
                .thenReturn(CompletableFuture.failedFuture(new org.apache.kafka.common.errors.TimeoutException("broker down")));

        publisher.publishPendingEvents();

        assertThat(ok.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
        assertThat(broken.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(broken.getNextAttemptAt()).isNotNull();
        verify(repository, times(1)).claimPublishable(any(), anyInt());
    }

    @Test
    void transientFailureSchedulesBackoffRecordsErrorAndStopsTheBatch() {
        OutboxEvent event = pendingEvent();
        when(repository.claimPublishable(any(), anyInt())).thenReturn(List.of(event));
        when(eventPublisher.publish(any()))
                .thenReturn(CompletableFuture.failedFuture(new org.apache.kafka.common.errors.TimeoutException("broker down")));

        LocalDateTime before = LocalDateTime.now();
        publisher.publishPendingEvents();

        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(event.getAttemptCount()).isEqualTo(1);
        assertThat(event.getNextAttemptAt()).isAfter(before);
        assertThat(event.getLastError()).contains("broker down");
        verify(repository, times(1)).claimPublishable(any(), anyInt());
    }

    @Test
    void eventThatCanNeverBeSerializedIsParkedAsFailed() {
        OutboxEvent event = pendingEvent();
        when(repository.claimPublishable(any(), anyInt())).thenReturn(List.of(event));
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
