package com.shylesh.webhook_service.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shylesh.webhook_service.event.EventEnvelope;
import com.shylesh.webhook_service.payload.WebhookPayloadFactory;
import com.shylesh.webhook_service.persistence.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WebhookEventServiceImplTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private ProcessedEventRepository processedEventRepository;
    private MerchantWebhookSubscriptionRepository subscriptionRepository;
    private WebhookDeliveryRepository deliveryRepository;
    private WebhookEventServiceImpl service;

    @BeforeEach
    void setUp() {
        processedEventRepository = mock(ProcessedEventRepository.class);
        subscriptionRepository = mock(MerchantWebhookSubscriptionRepository.class);
        deliveryRepository = mock(WebhookDeliveryRepository.class);

        service = new WebhookEventServiceImpl(
                processedEventRepository,
                subscriptionRepository,
                deliveryRepository,
                new WebhookPayloadFactory(objectMapper),
                objectMapper
        );
    }

    private EventEnvelope envelope(UUID eventId, String eventType, UUID paymentId, UUID merchantId) {
        Map<String, Object> data = new HashMap<>();
        data.put("paymentId", paymentId.toString());
        data.put("amount", "100.00");
        data.put("currency", "USD");
        data.put("merchantId", merchantId == null ? null : merchantId.toString());
        data.put("customerId", UUID.randomUUID().toString());
        return new EventEnvelope(eventId, eventType, LocalDateTime.now(), objectMapper.valueToTree(data));
    }

    private MerchantWebhookSubscription activeSubscription(UUID merchantId) {
        return MerchantWebhookSubscription.builder()
                .id(UUID.randomUUID())
                .merchantId(merchantId)
                .url("https://merchant.example.com/hooks")
                .secret("s3cret")
                .active(true)
                .build();
    }

    @Test
    void createsPendingDeliveryForMerchantWithActiveSubscription() {
        UUID eventId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        MerchantWebhookSubscription subscription = activeSubscription(merchantId);

        when(processedEventRepository.existsById(eventId)).thenReturn(false);
        when(subscriptionRepository.findByMerchantIdAndActiveTrue(merchantId)).thenReturn(Optional.of(subscription));

        service.handle(envelope(eventId, "PAYMENT_CREATED", paymentId, merchantId));

        ArgumentCaptor<WebhookDelivery> captor = ArgumentCaptor.forClass(WebhookDelivery.class);
        verify(deliveryRepository).save(captor.capture());

        WebhookDelivery saved = captor.getValue();
        assertThat(saved.getEventId()).isEqualTo(eventId);
        assertThat(saved.getPaymentId()).isEqualTo(paymentId);
        assertThat(saved.getMerchantId()).isEqualTo(merchantId);
        assertThat(saved.getSubscriptionId()).isEqualTo(subscription.getId());
        assertThat(saved.getUrl()).isEqualTo(subscription.getUrl());
        assertThat(saved.getStatus()).isEqualTo(WebhookDeliveryStatus.PENDING);
        assertThat(saved.getNextAttemptAt()).isNotNull();
        assertThat(saved.getPayload()).contains("\"payloadVersion\":\"1\"").contains(eventId.toString());

        verify(processedEventRepository).save(any());
    }

    @Test
    void marksEventProcessedWithoutDeliveryWhenMerchantHasNoSubscription() {
        UUID eventId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();

        when(processedEventRepository.existsById(eventId)).thenReturn(false);
        when(subscriptionRepository.findByMerchantIdAndActiveTrue(merchantId)).thenReturn(Optional.empty());

        service.handle(envelope(eventId, "PAYMENT_CREATED", UUID.randomUUID(), merchantId));

        verifyNoInteractions(deliveryRepository);
        verify(processedEventRepository).save(any());
    }

    @Test
    void treatsMissingMerchantIdAsNoSubscription() {
        UUID eventId = UUID.randomUUID();
        when(processedEventRepository.existsById(eventId)).thenReturn(false);

        service.handle(envelope(eventId, "PAYMENT_CREATED", UUID.randomUUID(), null));

        verifyNoInteractions(subscriptionRepository, deliveryRepository);
        verify(processedEventRepository).save(any());
    }

    @Test
    void skipsAlreadyProcessedEvent() {
        UUID eventId = UUID.randomUUID();
        when(processedEventRepository.existsById(eventId)).thenReturn(true);

        service.handle(envelope(eventId, "PAYMENT_CREATED", UUID.randomUUID(), UUID.randomUUID()));

        verifyNoInteractions(subscriptionRepository, deliveryRepository);
        verify(processedEventRepository, never()).save(any());
    }

    @Test
    void rejectsEnvelopeWithoutData() {
        UUID eventId = UUID.randomUUID();
        when(processedEventRepository.existsById(eventId)).thenReturn(false);

        EventEnvelope empty = new EventEnvelope(eventId, "PAYMENT_CREATED", LocalDateTime.now(), null);

        assertThatThrownBy(() -> service.handle(empty)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(deliveryRepository);
    }
}
