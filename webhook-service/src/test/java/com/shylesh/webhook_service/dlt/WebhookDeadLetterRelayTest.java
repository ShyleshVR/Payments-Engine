package com.shylesh.webhook_service.dlt;

import com.shylesh.webhook_service.persistence.WebhookDelivery;
import com.shylesh.webhook_service.persistence.WebhookDeliveryRepository;
import com.shylesh.webhook_service.persistence.WebhookDeliveryStatus;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class WebhookDeadLetterRelayTest {

    private WebhookDeliveryRepository deliveryRepository;
    private WebhookDeadLetterPublisher publisher;
    private MeterRegistry meterRegistry;
    private WebhookDeadLetterRelay relay;

    @BeforeEach
    void setUp() {
        deliveryRepository = mock(WebhookDeliveryRepository.class);
        publisher = mock(WebhookDeadLetterPublisher.class);
        meterRegistry = new SimpleMeterRegistry();
        relay = new WebhookDeadLetterRelay(deliveryRepository, publisher, meterRegistry);
    }

    private static WebhookDelivery failed() {
        return WebhookDelivery.builder()
                .id(UUID.randomUUID())
                .eventId(UUID.randomUUID())
                .eventType("PAYMENT_FAILED")
                .paymentId(UUID.randomUUID())
                .merchantId(UUID.randomUUID())
                .subscriptionId(UUID.randomUUID())
                .url("https://merchant.example.com/hooks")
                .payload("{}")
                .status(WebhookDeliveryStatus.FAILED)
                .attemptCount(5)
                .lastError("HTTP 500")
                .build();
    }

    private void pending(WebhookDelivery... deliveries) {
        when(deliveryRepository.findTop100ByStatusAndDltPublishedAtIsNullOrderByUpdatedAtAsc(WebhookDeliveryStatus.FAILED))
                .thenReturn(List.of(deliveries));
    }

    @Test
    void marksPublishedAndCountsOnlyAfterKafkaConfirms() throws DeadLetterPublishException {
        WebhookDelivery first = failed();
        WebhookDelivery second = failed();
        pending(first, second);

        relay.relayPendingDeadLetters();

        var order = inOrder(publisher, deliveryRepository);
        order.verify(publisher).publish(first);
        order.verify(deliveryRepository).markDeadLetterPublished(eq(first.getId()), any(LocalDateTime.class));
        order.verify(publisher).publish(second);
        order.verify(deliveryRepository).markDeadLetterPublished(eq(second.getId()), any(LocalDateTime.class));

        assertThat(meterRegistry.counter("webhooks.dlt", "eventType", "PAYMENT_FAILED").count()).isEqualTo(2.0);
    }

    @Test
    void leavesDeadLetterPendingAndStopsBatchWhenKafkaRefuses() throws DeadLetterPublishException {
        WebhookDelivery first = failed();
        WebhookDelivery second = failed();
        pending(first, second);
        doThrow(new DeadLetterPublishException("broker unavailable", new RuntimeException()))
                .when(publisher).publish(first);

        relay.relayPendingDeadLetters();

        verify(publisher).publish(first);
        verify(publisher, never()).publish(second);
        verify(deliveryRepository, never()).markDeadLetterPublished(any(), any());

        assertThat(meterRegistry.counter("webhooks.dlt", "eventType", "PAYMENT_FAILED").count()).isZero();
        assertThat(meterRegistry.counter("webhooks.dlt.publish.failed").count()).isEqualTo(1.0);
    }

    @Test
    void retriesStillPendingDeadLetterOnNextRun() throws DeadLetterPublishException {
        WebhookDelivery delivery = failed();
        pending(delivery);
        doThrow(new DeadLetterPublishException("broker unavailable", new RuntimeException()))
                .doNothing()
                .when(publisher).publish(delivery);

        relay.relayPendingDeadLetters();   // Kafka down
        relay.relayPendingDeadLetters();   // Kafka back

        verify(publisher, times(2)).publish(delivery);
        verify(deliveryRepository, times(1)).markDeadLetterPublished(eq(delivery.getId()), any(LocalDateTime.class));
    }
}
