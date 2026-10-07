package com.shylesh.webhook_service.service.impl;

import com.shylesh.webhook_service.TestProperties;
import com.shylesh.webhook_service.config.WebhookProperties;
import com.shylesh.webhook_service.http.HttpOutcome;
import com.shylesh.webhook_service.http.WebhookHttpClient;
import com.shylesh.webhook_service.persistence.*;
import com.shylesh.webhook_service.retry.WebhookRetryPolicy;
import com.shylesh.webhook_service.security.WebhookTargetValidator;
import com.shylesh.webhook_service.signing.WebhookSigner;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class WebhookDeliveryServiceImplTest {

    private static final String PAYLOAD = "{\"payloadVersion\":\"1\"}";

    private WebhookDeliveryRepository deliveryRepository;
    private WebhookDeliveryAttemptRepository attemptRepository;
    private MerchantWebhookSubscriptionRepository subscriptionRepository;
    private WebhookHttpClient httpClient;
    private PlatformTransactionManager transactionManager;
    private WebhookSigner signer;
    private SimpleMeterRegistry meterRegistry;
    private WebhookDeliveryServiceImpl service;

    private MerchantWebhookSubscription subscription;
    private WebhookDelivery delivery;

    @BeforeEach
    void setUp() {
        deliveryRepository = mock(WebhookDeliveryRepository.class);
        attemptRepository = mock(WebhookDeliveryAttemptRepository.class);
        subscriptionRepository = mock(MerchantWebhookSubscriptionRepository.class);
        httpClient = mock(WebhookHttpClient.class);
        transactionManager = mock(PlatformTransactionManager.class);
        signer = new WebhookSigner();
        meterRegistry = new SimpleMeterRegistry();

        // Public IP literal so the real target check passes without DNS.
        WebhookProperties properties = TestProperties.defaults();
        service = new WebhookDeliveryServiceImpl(
                deliveryRepository,
                attemptRepository,
                subscriptionRepository,
                signer,
                httpClient,
                new WebhookTargetValidator(properties),
                new WebhookRetryPolicy(properties),
                properties,
                new TransactionTemplate(transactionManager),
                meterRegistry
        );

        subscription = MerchantWebhookSubscription.builder()
                .id(UUID.randomUUID())
                .merchantId(UUID.randomUUID())
                .url("https://93.184.216.34/hooks")
                .secret("whsec_test")
                .active(true)
                .createdAt(LocalDateTime.now())
                .build();
        delivery = delivery(WebhookDeliveryStatus.PENDING, 0, subscription.getUrl());
        when(subscriptionRepository.findById(subscription.getId())).thenReturn(Optional.of(subscription));
    }

    private WebhookDelivery delivery(WebhookDeliveryStatus status, int attempts, String url) {
        WebhookDelivery d = WebhookDelivery.builder()
                .id(UUID.randomUUID())
                .eventId(UUID.randomUUID())
                .eventType("PAYMENT_COMPLETED")
                .paymentId(UUID.randomUUID())
                .merchantId(subscription.getMerchantId())
                .subscriptionId(subscription.getId())
                .url(url)
                .payload(PAYLOAD)
                .status(status)
                .attemptCount(attempts)
                .nextAttemptAt(LocalDateTime.now())
                .createdAt(LocalDateTime.now())
                .build();
        when(deliveryRepository.lockIfDue(eq(d.getId()), any())).thenReturn(Optional.of(d));
        when(deliveryRepository.findById(d.getId())).thenReturn(Optional.of(d));
        return d;
    }

    private void merchantResponds(HttpOutcome outcome) {
        when(httpClient.post(any(), anyMap(), any())).thenReturn(outcome);
    }

    private WebhookDeliveryAttempt recordedAttempt() {
        ArgumentCaptor<WebhookDeliveryAttempt> captor = ArgumentCaptor.forClass(WebhookDeliveryAttempt.class);
        verify(attemptRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void deliversWithAVerifiableTimestampedSignature() {
        merchantResponds(HttpOutcome.forStatus(200, "", 12));

        service.attemptDelivery(delivery.getId());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> headers = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
        verify(httpClient).post(eq(URI.create(subscription.getUrl())), headers.capture(), body.capture());

        assertThat(new String(body.getValue(), StandardCharsets.UTF_8)).isEqualTo(PAYLOAD);
        long timestamp = Long.parseLong(headers.getValue().get(WebhookSigner.TIMESTAMP_HEADER));
        assertThat(headers.getValue().get(WebhookSigner.SIGNATURE_HEADER))
                .isEqualTo(signer.sign("whsec_test", timestamp, body.getValue()));

        assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.DELIVERED);
        assertThat(delivery.getAttemptCount()).isEqualTo(1);
        WebhookDeliveryAttempt attempt = recordedAttempt();
        assertThat(attempt.getStatus()).isEqualTo(DeliveryAttemptStatus.SUCCESS);
        assertThat(attempt.getResponseCode()).isEqualTo(200);
        assertThat(attempt.getDurationMs()).isEqualTo(12);
        assertThat(meterRegistry.counter("webhooks.delivered", "eventType", "PAYMENT_COMPLETED").count()).isEqualTo(1);
    }

    @Test
    void sendsOutsideAnyTransaction() {
        merchantResponds(HttpOutcome.forStatus(200, "", 1));

        service.attemptDelivery(delivery.getId());

        var inOrder = inOrder(transactionManager, httpClient);
        inOrder.verify(transactionManager).commit(any());
        inOrder.verify(httpClient).post(any(), anyMap(), any());
        inOrder.verify(transactionManager).commit(any());
    }

    @Test
    void serverErrorIsRetriedWithBackoff() {
        merchantResponds(HttpOutcome.forStatus(503, "maintenance", 5));

        service.attemptDelivery(delivery.getId());

        assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.RETRYING);
        assertThat(delivery.getAttemptCount()).isEqualTo(1);
        assertThat(delivery.getNextAttemptAt()).isAfter(LocalDateTime.now().plusSeconds(20));
        assertThat(delivery.getLastError()).isEqualTo("HTTP 503: maintenance");
        assertThat(recordedAttempt().getResponseCode()).isEqualTo(503);
    }

    @Test
    void clientErrorFailsImmediatelyWithoutRetries() {
        merchantResponds(HttpOutcome.forStatus(400, "bad signature", 5));

        service.attemptDelivery(delivery.getId());

        assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.FAILED);
        assertThat(delivery.getAttemptCount()).isEqualTo(1);
        assertThat(delivery.getDltPublishedAt()).isNull();
        assertThat(meterRegistry.counter("webhooks.failed", "eventType", "PAYMENT_COMPLETED", "reason", "permanent").count())
                .isEqualTo(1);
    }

    @Test
    void retryableFailureOnTheLastAttemptFails() {
        delivery = delivery(WebhookDeliveryStatus.RETRYING, 4, subscription.getUrl());
        merchantResponds(HttpOutcome.retryable("Timed out", 10_000));

        service.attemptDelivery(delivery.getId());

        assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.FAILED);
        assertThat(delivery.getAttemptCount()).isEqualTo(5);
        assertThat(meterRegistry.counter("webhooks.failed", "eventType", "PAYMENT_COMPLETED", "reason", "exhausted").count())
                .isEqualTo(1);
    }

    @Test
    void unexpectedExceptionIsARecordedAttemptNotASilentLoop() {
        when(httpClient.post(any(), anyMap(), any())).thenThrow(new IllegalStateException("boom"));

        service.attemptDelivery(delivery.getId());

        assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.RETRYING);
        assertThat(delivery.getAttemptCount()).isEqualTo(1);
        assertThat(recordedAttempt().getErrorMessage()).contains("boom");
    }

    @Test
    void urlThatNowResolvesPrivatelyFailsWithoutBeingCalled() {
        delivery = delivery(WebhookDeliveryStatus.PENDING, 0, "https://169.254.169.254/latest/meta-data");

        service.attemptDelivery(delivery.getId());

        verifyNoInteractions(httpClient);
        assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.FAILED);
        assertThat(recordedAttempt().getResponseCode()).isNull();
    }

    @Test
    void inactiveSubscriptionCancelsTheDeliveryWithoutSending() {
        subscription.deactivate(LocalDateTime.now());

        service.attemptDelivery(delivery.getId());

        verifyNoInteractions(httpClient, attemptRepository);
        assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.CANCELLED);
    }

    @Test
    void doesNothingWhenTheDeliveryCannotBeClaimed() {
        UUID claimedElsewhere = UUID.randomUUID();
        when(deliveryRepository.lockIfDue(eq(claimedElsewhere), any())).thenReturn(Optional.empty());

        service.attemptDelivery(claimedElsewhere);

        verifyNoInteractions(httpClient, attemptRepository);
    }

    @Test
    void discardsTheOutcomeIfTheDeliveryWasCancelledDuringTheSend() {
        merchantResponds(HttpOutcome.forStatus(200, "", 1));
        WebhookDelivery cancelledMeanwhile = WebhookDelivery.builder()
                .id(delivery.getId())
                .status(WebhookDeliveryStatus.CANCELLED)
                .attemptCount(0)
                .build();
        when(deliveryRepository.findById(delivery.getId())).thenReturn(Optional.of(cancelledMeanwhile));

        service.attemptDelivery(delivery.getId());

        assertThat(cancelledMeanwhile.getStatus()).isEqualTo(WebhookDeliveryStatus.CANCELLED);
        verifyNoInteractions(attemptRepository);
    }

    @Test
    void urlNoLongerAllowedByPolicyFailsPermanently() {
        delivery = delivery(WebhookDeliveryStatus.PENDING, 0, "http://93.184.216.34/hooks");

        service.attemptDelivery(delivery.getId());

        verifyNoInteractions(httpClient);
        assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.FAILED);
        assertThat(delivery.getLastError()).contains("https");
    }
}
