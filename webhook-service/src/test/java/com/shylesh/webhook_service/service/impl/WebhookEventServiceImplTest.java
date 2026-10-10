package com.shylesh.webhook_service.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shylesh.webhook_service.event.PaymentEvent;
import com.shylesh.webhook_service.event.PaymentEventData;
import com.shylesh.webhook_service.payload.WebhookPayloadFactory;
import com.shylesh.webhook_service.persistence.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WebhookEventServiceImplTest {

    private ProcessedEventRepository processedEventRepository;
    private MerchantWebhookSubscriptionRepository subscriptionRepository;
    private WebhookDeliveryRepository deliveryRepository;
    private WebhookEventServiceImpl service;

    private UUID merchantId;
    private MerchantWebhookSubscription subscription;

    @BeforeEach
    void setUp() {
        processedEventRepository = mock(ProcessedEventRepository.class);
        subscriptionRepository = mock(MerchantWebhookSubscriptionRepository.class);
        deliveryRepository = mock(WebhookDeliveryRepository.class);

        service = new WebhookEventServiceImpl(
                processedEventRepository,
                subscriptionRepository,
                deliveryRepository,
                new WebhookPayloadFactory(new ObjectMapper().findAndRegisterModules())
        );

        merchantId = UUID.randomUUID();
        subscription = MerchantWebhookSubscription.builder()
                .id(UUID.randomUUID())
                .merchantId(merchantId)
                .url("https://merchant.example/hooks")
                .secret("whsec_x")
                .active(true)
                .createdAt(LocalDateTime.now())
                .build();
    }

    private PaymentEvent event(String type) {
        return new PaymentEvent(UUID.randomUUID(), type, LocalDateTime.now(),
                new PaymentEventData(UUID.randomUUID(), new BigDecimal("200.00"), "USD", merchantId, null, null, null));
    }

    @Test
    void queuesAPendingDeliveryWithTheRenderedPayloadForAnActiveSubscription() {
        PaymentEvent event = event("PAYMENT_COMPLETED");
        when(subscriptionRepository.findByMerchantIdAndActiveTrue(merchantId)).thenReturn(Optional.of(subscription));

        service.handle(event);

        ArgumentCaptor<WebhookDelivery> captor = ArgumentCaptor.forClass(WebhookDelivery.class);
        verify(deliveryRepository).save(captor.capture());
        WebhookDelivery delivery = captor.getValue();
        assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.PENDING);
        assertThat(delivery.getEventId()).isEqualTo(event.eventId());
        assertThat(delivery.getSubscriptionId()).isEqualTo(subscription.getId());
        assertThat(delivery.getUrl()).isEqualTo(subscription.getUrl());
        assertThat(delivery.getNextAttemptAt()).isNotNull();
        assertThat(delivery.getPayload())
                .contains("\"payloadVersion\":\"1.1\"")
                .contains("\"paymentId\":\"pay_" + event.data().getPaymentId() + "\"");
        verify(processedEventRepository).save(any());
    }

    @Test
    void paymentFailedIsDeliveredToo() {
        when(subscriptionRepository.findByMerchantIdAndActiveTrue(merchantId)).thenReturn(Optional.of(subscription));

        service.handle(event("PAYMENT_FAILED"));

        verify(deliveryRepository).save(any());
    }

    @Test
    void merchantWithoutSubscriptionIsMarkedProcessedWithNoDelivery() {
        when(subscriptionRepository.findByMerchantIdAndActiveTrue(merchantId)).thenReturn(Optional.empty());

        service.handle(event("PAYMENT_COMPLETED"));

        verifyNoInteractions(deliveryRepository);
        verify(processedEventRepository).save(any());
    }

    @Test
    void unsupportedEventTypeIsMarkedProcessedWithoutLookingUpSubscriptions() {
        service.handle(event("PAYMENT_DISPUTED"));

        verifyNoInteractions(deliveryRepository, subscriptionRepository);
        verify(processedEventRepository).save(any());
    }

    @Test
    void alreadyProcessedEventIsSkipped() {
        PaymentEvent event = event("PAYMENT_COMPLETED");
        when(processedEventRepository.existsById(event.eventId())).thenReturn(true);

        service.handle(event);

        verifyNoInteractions(deliveryRepository, subscriptionRepository);
        verify(processedEventRepository, never()).save(any());
    }

    private com.shylesh.webhook_service.event.PayoutEvent payoutEvent(String type, String status, String failureCode) {
        return new com.shylesh.webhook_service.event.PayoutEvent(UUID.randomUUID(), type, LocalDateTime.now(),
                new com.shylesh.webhook_service.event.PayoutEventData(UUID.randomUUID(), merchantId, new BigDecimal("75.50"),
                        "USD", status, "BATCH", failureCode));
    }

    @Test
    void aPayoutEventIsQueuedWithItsOwnPayloadAndThePayoutId() {
        var event = payoutEvent("PAYOUT_RETURNED", "RETURNED", "account_frozen");
        when(subscriptionRepository.findByMerchantIdAndActiveTrue(merchantId)).thenReturn(Optional.of(subscription));

        service.handle(event);

        ArgumentCaptor<WebhookDelivery> captor = ArgumentCaptor.forClass(WebhookDelivery.class);
        verify(deliveryRepository).save(captor.capture());
        WebhookDelivery delivery = captor.getValue();
        assertThat(delivery.getPayoutId()).isEqualTo(event.data().getPayoutId());
        assertThat(delivery.getPaymentId()).isNull();
        assertThat(delivery.getPayload())
                .contains("\"payloadVersion\":\"1.0\"")
                .contains("\"payoutId\":\"po_" + event.data().getPayoutId() + "\"")
                .contains("\"amount\":75.50")
                .contains("\"status\":\"RETURNED\"")
                .contains("\"failureCode\":\"account_frozen\"")
                .doesNotContain("paymentId");
    }

    @Test
    void anUnknownPayoutEventTypeIsSkipped() {
        service.handle(payoutEvent("PAYOUT_SCHEDULED", "PENDING", null));

        verifyNoInteractions(deliveryRepository, subscriptionRepository);
        verify(processedEventRepository).save(any());
    }
}
