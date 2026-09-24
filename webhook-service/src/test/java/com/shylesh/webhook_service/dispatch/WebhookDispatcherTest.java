package com.shylesh.webhook_service.dispatch;

import com.shylesh.webhook_service.persistence.WebhookDelivery;
import com.shylesh.webhook_service.persistence.WebhookDeliveryRepository;
import com.shylesh.webhook_service.persistence.WebhookDeliveryStatus;
import com.shylesh.webhook_service.service.WebhookDeliveryService;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WebhookDispatcherTest {

    private static WebhookDelivery pending() {
        return WebhookDelivery.builder()
                .id(UUID.randomUUID())
                .eventId(UUID.randomUUID())
                .eventType("PAYMENT_CREATED")
                .paymentId(UUID.randomUUID())
                .merchantId(UUID.randomUUID())
                .subscriptionId(UUID.randomUUID())
                .url("https://merchant.example.com/hooks")
                .payload("{}")
                .status(WebhookDeliveryStatus.PENDING)
                .build();
    }

    @Test
    void continuesDispatchingRemainingDeliveriesWhenOneFails() {
        WebhookDeliveryRepository deliveryRepository = mock(WebhookDeliveryRepository.class);
        WebhookDeliveryService deliveryService = mock(WebhookDeliveryService.class);

        WebhookDelivery first = pending();
        WebhookDelivery second = pending();

        when(deliveryRepository.findTop100ByStatusInAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(any(), any()))
                .thenReturn(List.of(first, second));
        doThrow(new RuntimeException("boom")).when(deliveryService).attemptDelivery(first.getId());

        new WebhookDispatcher(deliveryRepository, deliveryService).dispatchDueDeliveries();

        verify(deliveryService).attemptDelivery(first.getId());
        verify(deliveryService).attemptDelivery(second.getId());
    }
}
