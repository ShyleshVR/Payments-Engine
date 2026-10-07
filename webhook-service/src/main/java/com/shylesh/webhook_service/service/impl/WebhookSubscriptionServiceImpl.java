package com.shylesh.webhook_service.service.impl;

import com.shylesh.webhook_service.dto.CreateWebhookSubscriptionRequest;
import com.shylesh.webhook_service.dto.WebhookSubscriptionResponse;
import com.shylesh.webhook_service.exception.SubscriptionAlreadyExistsException;
import com.shylesh.webhook_service.exception.SubscriptionNotFoundException;
import com.shylesh.webhook_service.persistence.MerchantWebhookSubscription;
import com.shylesh.webhook_service.persistence.MerchantWebhookSubscriptionRepository;
import com.shylesh.webhook_service.persistence.WebhookDeliveryRepository;
import com.shylesh.webhook_service.security.WebhookTargetValidator;
import com.shylesh.webhook_service.service.WebhookSubscriptionService;
import com.shylesh.webhook_service.signing.WebhookSigner;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class WebhookSubscriptionServiceImpl implements WebhookSubscriptionService {

    static final String UNSUBSCRIBED_REASON = "Subscription deleted before delivery";

    private final MerchantWebhookSubscriptionRepository subscriptionRepository;
    private final WebhookDeliveryRepository deliveryRepository;
    private final WebhookTargetValidator targetValidator;
    private final WebhookSigner signer;

    @Override
    @Transactional
    public WebhookSubscriptionResponse create(CreateWebhookSubscriptionRequest request) {
        String url = targetValidator.validateForSubscription(request.url()).toString();

        if (subscriptionRepository.findByMerchantIdAndActiveTrue(request.merchantId()).isPresent()) {
            throw new SubscriptionAlreadyExistsException(request.merchantId());
        }

        MerchantWebhookSubscription subscription = MerchantWebhookSubscription.builder()
                .id(UUID.randomUUID())
                .merchantId(request.merchantId())
                .url(url)
                .secret(signer.newSecret())
                .active(true)
                .createdAt(LocalDateTime.now())
                .build();

        try {
            subscriptionRepository.saveAndFlush(subscription);
        } catch (DataIntegrityViolationException e) {
            // Lost a race with a concurrent create for the same merchant (unique active index).
            throw new SubscriptionAlreadyExistsException(request.merchantId());
        }

        log.info("Webhook subscription created. subscriptionId={}, merchantId={}", subscription.getId(), subscription.getMerchantId());
        return WebhookSubscriptionResponse.withSecret(subscription);
    }

    @Override
    @Transactional(readOnly = true)
    public WebhookSubscriptionResponse get(UUID merchantId) {
        return subscriptionRepository.findByMerchantIdAndActiveTrue(merchantId)
                .map(WebhookSubscriptionResponse::withoutSecret)
                .orElseThrow(() -> new SubscriptionNotFoundException(merchantId));
    }

    /**
     * Soft delete: the row stays for the audit trail of its deliveries. Open deliveries are
     * cancelled in the same transaction, so nothing more is sent to an endpoint the merchant
     * has unsubscribed.
     */
    @Override
    @Transactional
    public void deactivate(UUID merchantId) {
        MerchantWebhookSubscription subscription = subscriptionRepository.findByMerchantIdAndActiveTrue(merchantId)
                .orElseThrow(() -> new SubscriptionNotFoundException(merchantId));

        LocalDateTime now = LocalDateTime.now();
        subscription.deactivate(now);
        subscriptionRepository.saveAndFlush(subscription);

        int cancelled = deliveryRepository.cancelOpenDeliveries(subscription.getId(), UNSUBSCRIBED_REASON, now);

        log.info("Webhook subscription deactivated. subscriptionId={}, merchantId={}, cancelledDeliveries={}",
                subscription.getId(), merchantId, cancelled);
    }
}
