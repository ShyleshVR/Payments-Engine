package com.shylesh.webhook_service.service.impl;

import com.shylesh.webhook_service.dlt.WebhookDeadLetterPublisher;
import com.shylesh.webhook_service.http.WebhookDeliveryException;
import com.shylesh.webhook_service.http.WebhookHttpClient;
import com.shylesh.webhook_service.persistence.*;
import com.shylesh.webhook_service.retry.WebhookRetryPolicy;
import com.shylesh.webhook_service.signing.WebhookSigner;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class WebhookDeliveryServiceImplTest {

    private static final String PAYLOAD = "{\"payloadVersion\":\"1\",\"eventId\":\"x\"}";
    private static final String SECRET = "whsec_test";

    private WebhookDeliveryRepository deliveryRepository;
    private WebhookDeliveryAttemptRepository attemptRepository;
    private MerchantWebhookSubscriptionRepository subscriptionRepository;
    private WebhookHttpClient httpClient;
    private WebhookRetryPolicy retryPolicy;
    private WebhookDeadLetterPublisher deadLetterPublisher;
    private MeterRegistry meterRegistry;
    private final WebhookSigner signer = new WebhookSigner();
    private WebhookDeliveryServiceImpl service;

    private MerchantWebhookSubscription subscription;
    private WebhookDelivery delivery;

    @BeforeEach
    void setUp() {
        deliveryRepository = mock(WebhookDeliveryRepository.class);
        attemptRepository = mock(WebhookDeliveryAttemptRepository.class);
        subscriptionRepository = mock(MerchantWebhookSubscriptionRepository.class);
        httpClient = mock(WebhookHttpClient.class);
        retryPolicy = mock(WebhookRetryPolicy.class);
        deadLetterPublisher = mock(WebhookDeadLetterPublisher.class);
        meterRegistry = new SimpleMeterRegistry();

        service = new WebhookDeliveryServiceImpl(
                deliveryRepository,
                attemptRepository,
                subscriptionRepository,
                signer,
                httpClient,
                retryPolicy,
                deadLetterPublisher,
                meterRegistry
        );

        subscription = MerchantWebhookSubscription.builder()
                .id(UUID.randomUUID())
                .merchantId(UUID.randomUUID())
                .url("https://merchant.example.com/hooks")
                .secret(SECRET)
                .active(true)
                .build();

        delivery = delivery(WebhookDeliveryStatus.PENDING, 0);

        when(subscriptionRepository.findById(subscription.getId())).thenReturn(Optional.of(subscription));
    }

    private WebhookDelivery delivery(WebhookDeliveryStatus status, int attemptCount) {
        WebhookDelivery d = WebhookDelivery.builder()
                .id(UUID.randomUUID())
                .eventId(UUID.randomUUID())
                .eventType("PAYMENT_COMPLETED")
                .paymentId(UUID.randomUUID())
                .merchantId(subscription.getMerchantId())
                .subscriptionId(subscription.getId())
                .url(subscription.getUrl())
                .payload(PAYLOAD)
                .status(status)
                .attemptCount(attemptCount)
                .createdAt(LocalDateTime.now())
                .build();
        when(deliveryRepository.findById(d.getId())).thenReturn(Optional.of(d));
        return d;
    }

    @Test
    void signsExactPayloadBytesAndMarksDeliveredOn2xx() throws WebhookDeliveryException {
        byte[] expectedBody = PAYLOAD.getBytes(StandardCharsets.UTF_8);
        String expectedSignature = signer.sign(SECRET, expectedBody);
        when(httpClient.post(eq(subscription.getUrl()), eq(expectedSignature), eq(expectedBody))).thenReturn(200);

        service.attemptDelivery(delivery.getId());

        assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.DELIVERED);
        assertThat(delivery.getAttemptCount()).isEqualTo(1);

        ArgumentCaptor<WebhookDeliveryAttempt> captor = ArgumentCaptor.forClass(WebhookDeliveryAttempt.class);
        verify(attemptRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(DeliveryAttemptStatus.SUCCESS);
        assertThat(captor.getValue().getResponseCode()).isEqualTo(200);

        assertThat(meterRegistry.counter("webhooks.delivered", "eventType", "PAYMENT_COMPLETED").count())
                .isEqualTo(1.0);
        verifyNoInteractions(deadLetterPublisher);
    }

    @Test
    void marksRetryingAndRecordsResponseCodeWhenRetriesRemain() throws WebhookDeliveryException {
        when(httpClient.post(anyString(), anyString(), any()))
                .thenThrow(new WebhookDeliveryException("Merchant endpoint responded with HTTP 500", 500));
        when(retryPolicy.canRetry(1)).thenReturn(true);
        LocalDateTime nextAttempt = LocalDateTime.now().plusSeconds(30);
        when(retryPolicy.nextAttemptAt(1)).thenReturn(nextAttempt);

        service.attemptDelivery(delivery.getId());

        assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.RETRYING);
        assertThat(delivery.getAttemptCount()).isEqualTo(1);
        assertThat(delivery.getNextAttemptAt()).isEqualTo(nextAttempt);
        assertThat(delivery.getLastError()).contains("HTTP 500");

        ArgumentCaptor<WebhookDeliveryAttempt> captor = ArgumentCaptor.forClass(WebhookDeliveryAttempt.class);
        verify(attemptRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(DeliveryAttemptStatus.FAILURE);
        assertThat(captor.getValue().getResponseCode()).isEqualTo(500);

        assertThat(meterRegistry.counter("webhooks.retried", "eventType", "PAYMENT_COMPLETED").count())
                .isEqualTo(1.0);
        verifyNoInteractions(deadLetterPublisher);
    }

    @Test
    void marksFailedAndPublishesToDltWhenRetriesExhausted() throws WebhookDeliveryException {
        delivery = delivery(WebhookDeliveryStatus.RETRYING, 4);
        when(httpClient.post(anyString(), anyString(), any()))
                .thenThrow(new WebhookDeliveryException("connect timed out", (Integer) null));
        when(retryPolicy.canRetry(5)).thenReturn(false);

        service.attemptDelivery(delivery.getId());

        assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.FAILED);
        assertThat(delivery.getAttemptCount()).isEqualTo(5);
        verify(deadLetterPublisher).publish(delivery, "connect timed out");
    }

    @Test
    void cancelsWithoutCallingMerchantWhenSubscriptionDeactivated() {
        subscription.deactivate();

        service.attemptDelivery(delivery.getId());

        assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.CANCELLED);
        assertThat(delivery.getLastError()).isEqualTo(WebhookDeliveryServiceImpl.SUBSCRIPTION_INACTIVE_REASON);
        verifyNoInteractions(httpClient, attemptRepository, deadLetterPublisher);
    }

    @Test
    void ignoresDeliveryAlreadyInTerminalState() {
        WebhookDelivery delivered = delivery(WebhookDeliveryStatus.DELIVERED, 1);

        service.attemptDelivery(delivered.getId());

        verifyNoInteractions(httpClient, attemptRepository, deadLetterPublisher);
    }

    @Test
    void doesNothingWhenDeliveryNoLongerExists() {
        UUID missingId = UUID.randomUUID();
        when(deliveryRepository.findById(missingId)).thenReturn(Optional.empty());

        service.attemptDelivery(missingId);

        verifyNoInteractions(httpClient, attemptRepository, deadLetterPublisher);
    }
}
