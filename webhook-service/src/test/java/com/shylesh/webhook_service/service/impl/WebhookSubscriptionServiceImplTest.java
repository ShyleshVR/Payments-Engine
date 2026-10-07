package com.shylesh.webhook_service.service.impl;

import com.shylesh.webhook_service.TestProperties;
import com.shylesh.webhook_service.dto.CreateWebhookSubscriptionRequest;
import com.shylesh.webhook_service.dto.WebhookSubscriptionResponse;
import com.shylesh.webhook_service.exception.InvalidWebhookUrlException;
import com.shylesh.webhook_service.exception.SubscriptionAlreadyExistsException;
import com.shylesh.webhook_service.exception.SubscriptionNotFoundException;
import com.shylesh.webhook_service.persistence.MerchantWebhookSubscription;
import com.shylesh.webhook_service.persistence.MerchantWebhookSubscriptionRepository;
import com.shylesh.webhook_service.persistence.WebhookDeliveryRepository;
import com.shylesh.webhook_service.security.WebhookTargetValidator;
import com.shylesh.webhook_service.signing.WebhookSigner;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class WebhookSubscriptionServiceImplTest {

    private static final String URL = "https://93.184.216.34/hooks";

    private MerchantWebhookSubscriptionRepository subscriptionRepository;
    private WebhookDeliveryRepository deliveryRepository;
    private WebhookSubscriptionServiceImpl service;
    private UUID merchantId;

    @BeforeEach
    void setUp() {
        subscriptionRepository = mock(MerchantWebhookSubscriptionRepository.class);
        deliveryRepository = mock(WebhookDeliveryRepository.class);
        service = new WebhookSubscriptionServiceImpl(
                subscriptionRepository,
                deliveryRepository,
                new WebhookTargetValidator(TestProperties.defaults()),
                new WebhookSigner()
        );
        merchantId = UUID.randomUUID();
        when(subscriptionRepository.findByMerchantIdAndActiveTrue(any())).thenReturn(Optional.empty());
    }

    private MerchantWebhookSubscription active() {
        return MerchantWebhookSubscription.builder()
                .id(UUID.randomUUID())
                .merchantId(merchantId)
                .url(URL)
                .secret("whsec_x")
                .active(true)
                .createdAt(LocalDateTime.now())
                .build();
    }

    @Test
    void createReturnsTheSecretExactlyOnce() {
        WebhookSubscriptionResponse created = service.create(new CreateWebhookSubscriptionRequest(merchantId, URL));

        assertThat(created.secret()).startsWith("whsec_");
        assertThat(created.active()).isTrue();
        verify(subscriptionRepository).saveAndFlush(any());

        when(subscriptionRepository.findByMerchantIdAndActiveTrue(merchantId)).thenReturn(Optional.of(active()));
        assertThat(service.get(merchantId).secret()).isNull();
    }

    @Test
    void secondActiveSubscriptionIsRejected() {
        when(subscriptionRepository.findByMerchantIdAndActiveTrue(merchantId)).thenReturn(Optional.of(active()));

        assertThatThrownBy(() -> service.create(new CreateWebhookSubscriptionRequest(merchantId, URL)))
                .isInstanceOf(SubscriptionAlreadyExistsException.class);
        verify(subscriptionRepository, never()).saveAndFlush(any());
    }

    @Test
    void concurrentCreateLosingTheUniqueIndexRaceIsAConflict() {
        when(subscriptionRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uq_webhook_subscriptions_active_merchant"));

        assertThatThrownBy(() -> service.create(new CreateWebhookSubscriptionRequest(merchantId, URL)))
                .isInstanceOf(SubscriptionAlreadyExistsException.class);
    }

    @Test
    void internalUrlIsRejected() {
        assertThatThrownBy(() -> service.create(new CreateWebhookSubscriptionRequest(merchantId, "https://10.0.0.5/hooks")))
                .isInstanceOf(InvalidWebhookUrlException.class);
        verify(subscriptionRepository, never()).saveAndFlush(any());
    }

    @Test
    void deactivateSoftDeletesAndCancelsOpenDeliveries() {
        MerchantWebhookSubscription subscription = active();
        when(subscriptionRepository.findByMerchantIdAndActiveTrue(merchantId)).thenReturn(Optional.of(subscription));
        when(deliveryRepository.cancelOpenDeliveries(eq(subscription.getId()), any(), any())).thenReturn(3);

        service.deactivate(merchantId);

        assertThat(subscription.isActive()).isFalse();
        assertThat(subscription.getDeactivatedAt()).isNotNull();
        verify(deliveryRepository).cancelOpenDeliveries(eq(subscription.getId()), any(), any());
    }

    @Test
    void deactivatingAMissingSubscriptionIsNotFound() {
        assertThatThrownBy(() -> service.deactivate(merchantId)).isInstanceOf(SubscriptionNotFoundException.class);
        verifyNoInteractions(deliveryRepository);
    }
}
