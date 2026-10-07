package com.shylesh.webhook_service.dispatch;

import com.shylesh.webhook_service.config.WebhookProperties;
import com.shylesh.webhook_service.persistence.WebhookDelivery;
import com.shylesh.webhook_service.persistence.WebhookDeliveryRepository;
import com.shylesh.webhook_service.persistence.WebhookDeliveryStatus;
import com.shylesh.webhook_service.service.WebhookDeliveryService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WebhookDispatcherTest {

    private WebhookDeliveryRepository deliveryRepository;
    private WebhookDeliveryService deliveryService;
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        deliveryRepository = mock(WebhookDeliveryRepository.class);
        deliveryService = mock(WebhookDeliveryService.class);
        executor = Executors.newFixedThreadPool(4);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    private WebhookDispatcher dispatcher(int maxInFlight) {
        WebhookProperties properties = new WebhookProperties(
                new WebhookProperties.Delivery(maxInFlight, 4, Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(10)),
                new WebhookProperties.Retry(5, Duration.ofSeconds(30), Duration.ofMinutes(8)),
                new WebhookProperties.Security(true, false)
        );
        return new WebhookDispatcher(deliveryRepository, deliveryService, executor, properties);
    }

    private List<WebhookDelivery> due(int count) {
        List<WebhookDelivery> deliveries = IntStream.range(0, count)
                .mapToObj(i -> WebhookDelivery.builder().id(UUID.randomUUID()).status(WebhookDeliveryStatus.PENDING).build())
                .toList();
        when(deliveryRepository.findByStatusInAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(any(), any(), any()))
                .thenReturn(deliveries);
        return deliveries;
    }

    @Test
    void pollReturnsImmediatelyWhileDeliveriesRunInParallel() throws Exception {
        WebhookDispatcher dispatcher = dispatcher(100);
        List<WebhookDelivery> deliveries = due(4);
        CountDownLatch allStarted = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            allStarted.countDown();
            release.await(5, TimeUnit.SECONDS);
            return null;
        }).when(deliveryService).attemptDelivery(any());

        long start = System.nanoTime();
        dispatcher.dispatchDueDeliveries();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));

        // All four in flight at once: a slow merchant does not serialise the others.
        assertThat(allStarted.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(dispatcher.inFlightCount()).isEqualTo(4);

        release.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        deliveries.forEach(d -> verify(deliveryService).attemptDelivery(d.getId()));
        assertThat(dispatcher.inFlightCount()).isZero();
    }

    @Test
    void deliveryStillInFlightIsNotSubmittedAgainByTheNextPoll() throws Exception {
        WebhookDispatcher dispatcher = dispatcher(100);
        List<WebhookDelivery> deliveries = due(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> release.await(5, TimeUnit.SECONDS)).when(deliveryService).attemptDelivery(any());

        dispatcher.dispatchDueDeliveries();
        dispatcher.dispatchDueDeliveries();
        dispatcher.dispatchDueDeliveries();

        release.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        verify(deliveryService, times(1)).attemptDelivery(deliveries.get(0).getId());
    }

    @Test
    void neverExceedsMaxInFlight() throws Exception {
        WebhookDispatcher dispatcher = dispatcher(2);
        due(5);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> release.await(5, TimeUnit.SECONDS)).when(deliveryService).attemptDelivery(any());

        dispatcher.dispatchDueDeliveries();
        dispatcher.dispatchDueDeliveries();

        assertThat(dispatcher.inFlightCount()).isEqualTo(2);
        release.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        verify(deliveryService, times(2)).attemptDelivery(any());
    }

    @Test
    void oneFailingDeliveryDoesNotStopTheOthersAndIsReleased() throws Exception {
        WebhookDispatcher dispatcher = dispatcher(100);
        List<WebhookDelivery> deliveries = due(3);
        doThrow(new RuntimeException("db down")).when(deliveryService).attemptDelivery(deliveries.get(0).getId());

        dispatcher.dispatchDueDeliveries();

        executor.shutdown();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        deliveries.forEach(d -> verify(deliveryService).attemptDelivery(d.getId()));
        assertThat(dispatcher.inFlightCount()).isZero();
    }
}
