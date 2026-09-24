package com.shylesh.webhook_service.service.impl;

import com.shylesh.webhook_service.dto.CreateWebhookSubscriptionRequest;
import com.shylesh.webhook_service.dto.WebhookSubscriptionResponse;
import com.shylesh.webhook_service.exception.InvalidWebhookUrlException;
import com.shylesh.webhook_service.exception.SubscriptionAlreadyExistsException;
import com.shylesh.webhook_service.exception.SubscriptionNotFoundException;
import com.shylesh.webhook_service.persistence.MerchantWebhookSubscription;
import com.shylesh.webhook_service.persistence.MerchantWebhookSubscriptionRepository;
import com.shylesh.webhook_service.service.WebhookSubscriptionService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

/**
 * V1 subscription management. Unauthenticated by design for this phase — merchant
 * identity/auth is the next roadmap item and will close that gap. No secret rotation yet:
 * to change a secret, delete and re-create the subscription.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WebhookSubscriptionServiceImpl implements WebhookSubscriptionService {

    static final String SECRET_PREFIX = "whsec_";
    private static final int SECRET_BYTES = 32;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final MerchantWebhookSubscriptionRepository subscriptionRepository;

    @Override
    @Transactional
    public WebhookSubscriptionResponse create(CreateWebhookSubscriptionRequest request) {
        validateUrl(request.url());

        if (subscriptionRepository.findByMerchantIdAndActiveTrue(request.merchantId()).isPresent()) {
            throw new SubscriptionAlreadyExistsException(request.merchantId());
        }

        MerchantWebhookSubscription subscription = MerchantWebhookSubscription.builder()
                .id(UUID.randomUUID())
                .merchantId(request.merchantId())
                .url(request.url())
                .secret(generateSecret())
                .active(true)
                .build();

        MerchantWebhookSubscription saved = subscriptionRepository.saveAndFlush(subscription);

        log.info("Webhook subscription created. subscriptionId={}, merchantId={}", saved.getId(), saved.getMerchantId());

        return WebhookSubscriptionResponse.withSecret(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public WebhookSubscriptionResponse getActive(UUID merchantId) {
        return subscriptionRepository.findByMerchantIdAndActiveTrue(merchantId)
                .map(WebhookSubscriptionResponse::withoutSecret)
                .orElseThrow(() -> new SubscriptionNotFoundException(merchantId));
    }

    @Override
    @Transactional
    public void deactivate(UUID merchantId) {
        MerchantWebhookSubscription subscription = subscriptionRepository.findByMerchantIdAndActiveTrue(merchantId)
                .orElseThrow(() -> new SubscriptionNotFoundException(merchantId));

        subscription.deactivate();
        subscriptionRepository.save(subscription);

        log.info("Webhook subscription deactivated. subscriptionId={}, merchantId={}", subscription.getId(), merchantId);
    }

    private static void validateUrl(String url) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            boolean httpScheme = "https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme);
            if (!httpScheme || uri.getHost() == null) {
                throw new InvalidWebhookUrlException(url);
            }
        } catch (IllegalArgumentException e) {
            throw new InvalidWebhookUrlException(url);
        }
    }

    private static String generateSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return SECRET_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
