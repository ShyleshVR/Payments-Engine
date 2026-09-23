package com.shylesh.webhook_service.service.impl;

import com.shylesh.webhook_service.dto.CreateWebhookSubscriptionRequest;
import com.shylesh.webhook_service.dto.WebhookSubscriptionResponse;
import com.shylesh.webhook_service.exception.InvalidWebhookUrlException;
import com.shylesh.webhook_service.exception.SubscriptionAlreadyExistsException;
import com.shylesh.webhook_service.exception.SubscriptionNotFoundException;
import com.shylesh.webhook_service.persistence.MerchantWebhookSubscription;
import com.shylesh.webhook_service.persistence.MerchantWebhookSubscriptionRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WebhookSubscriptionServiceImplTest {

    private MerchantWebhookSubscriptionRepository repository;
    private WebhookSubscriptionServiceImpl service;

    @BeforeEach
    void setUp() {
        repository = mock(MerchantWebhookSubscriptionRepository.class);
        service = new WebhookSubscriptionServiceImpl(repository);
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private MerchantWebhookSubscription active(UUID merchantId) {
        return MerchantWebhookSubscription.builder()
                .id(UUID.randomUUID())
                .merchantId(merchantId)
                .url("https://merchant.example.com/hooks")
                .secret("whsec_existing")
                .active(true)
                .build();
    }

    @Test
    void createsActiveSubscriptionAndReturnsGeneratedSecretOnce() {
        UUID merchantId = UUID.randomUUID();
        when(repository.findByMerchantIdAndActiveTrue(merchantId)).thenReturn(Optional.empty());

        WebhookSubscriptionResponse response =
                service.create(new CreateWebhookSubscriptionRequest(merchantId, "https://merchant.example.com/hooks"));

        ArgumentCaptor<MerchantWebhookSubscription> captor = ArgumentCaptor.forClass(MerchantWebhookSubscription.class);
        verify(repository).saveAndFlush(captor.capture());
        MerchantWebhookSubscription saved = captor.getValue();

        assertThat(saved.isActive()).isTrue();
        assertThat(saved.getMerchantId()).isEqualTo(merchantId);
        assertThat(saved.getSecret()).startsWith(WebhookSubscriptionServiceImpl.SECRET_PREFIX).hasSizeGreaterThan(40);
        assertThat(response.secret()).isEqualTo(saved.getSecret());
    }

    @Test
    void generatesDifferentSecretsPerSubscription() {
        when(repository.findByMerchantIdAndActiveTrue(any())).thenReturn(Optional.empty());

        String first = service.create(new CreateWebhookSubscriptionRequest(UUID.randomUUID(), "https://a.example.com")).secret();
        String second = service.create(new CreateWebhookSubscriptionRequest(UUID.randomUUID(), "https://b.example.com")).secret();

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void rejectsSecondActiveSubscriptionForSameMerchant() {
        UUID merchantId = UUID.randomUUID();
        when(repository.findByMerchantIdAndActiveTrue(merchantId)).thenReturn(Optional.of(active(merchantId)));

        assertThatThrownBy(() -> service.create(new CreateWebhookSubscriptionRequest(merchantId, "https://x.example.com")))
                .isInstanceOf(SubscriptionAlreadyExistsException.class);
        verify(repository, never()).saveAndFlush(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ftp://merchant.example.com/hooks", "/relative/path", "not a url", "https://"})
    void rejectsNonHttpOrMalformedUrls(String url) {
        assertThatThrownBy(() -> service.create(new CreateWebhookSubscriptionRequest(UUID.randomUUID(), url)))
                .isInstanceOf(InvalidWebhookUrlException.class);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void getNeverExposesSecret() {
        UUID merchantId = UUID.randomUUID();
        when(repository.findByMerchantIdAndActiveTrue(merchantId)).thenReturn(Optional.of(active(merchantId)));

        assertThat(service.getActive(merchantId).secret()).isNull();
    }

    @Test
    void deactivateSoftDeletesActiveSubscription() {
        UUID merchantId = UUID.randomUUID();
        MerchantWebhookSubscription subscription = active(merchantId);
        when(repository.findByMerchantIdAndActiveTrue(merchantId)).thenReturn(Optional.of(subscription));

        service.deactivate(merchantId);

        assertThat(subscription.isActive()).isFalse();
        verify(repository).save(subscription);
        verify(repository, never()).delete(any());
    }

    @Test
    void deactivateThrowsWhenNoActiveSubscription() {
        UUID merchantId = UUID.randomUUID();
        when(repository.findByMerchantIdAndActiveTrue(merchantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deactivate(merchantId)).isInstanceOf(SubscriptionNotFoundException.class);
    }
}
