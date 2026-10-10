package com.shylesh.webhook_service.service.impl;

import com.shylesh.webhook_service.event.PaymentEvent;
import com.shylesh.webhook_service.event.PaymentEventType;
import com.shylesh.webhook_service.event.PayoutEvent;
import com.shylesh.webhook_service.event.PayoutEventType;
import com.shylesh.webhook_service.payload.WebhookPayloadFactory;
import com.shylesh.webhook_service.persistence.MerchantWebhookSubscription;
import com.shylesh.webhook_service.persistence.MerchantWebhookSubscriptionRepository;
import com.shylesh.webhook_service.persistence.ProcessedEvent;
import com.shylesh.webhook_service.persistence.ProcessedEventRepository;
import com.shylesh.webhook_service.persistence.WebhookDelivery;
import com.shylesh.webhook_service.persistence.WebhookDeliveryRepository;
import com.shylesh.webhook_service.persistence.WebhookDeliveryStatus;
import com.shylesh.webhook_service.service.WebhookEventService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Turns a payment or payout event into a PENDING delivery for the merchant's active
 * subscription. The delivery row and the processed_events marker commit together, so an event
 * is either fully accepted or redelivered by Kafka and handled again.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WebhookEventServiceImpl implements WebhookEventService {

    private final ProcessedEventRepository processedEventRepository;
    private final MerchantWebhookSubscriptionRepository subscriptionRepository;
    private final WebhookDeliveryRepository deliveryRepository;
    private final WebhookPayloadFactory payloadFactory;

    @Override
    @Transactional
    public void handle(PaymentEvent event) {
        enqueue(event.eventId(), event.eventType(), PaymentEventType.parse(event.eventType()).isPresent(),
                event.data().getMerchantId(), event.data().getPaymentId(), null,
                () -> payloadFactory.render(payloadFactory.create(event)));
    }

    @Override
    @Transactional
    public void handle(PayoutEvent event) {
        enqueue(event.eventId(), event.eventType(), PayoutEventType.parse(event.eventType()).isPresent(),
                event.data().getMerchantId(), null, event.data().getPayoutId(),
                () -> payloadFactory.render(payloadFactory.create(event)));
    }

    private void enqueue(UUID eventId, String eventType, boolean delivered, UUID merchantId, UUID paymentId, UUID payoutId,
                         java.util.function.Supplier<String> payload) {

        if (processedEventRepository.existsById(eventId)) {
            log.info("Skipping already-processed event. eventId={}, eventType={}", eventId, eventType);
            return;
        }

        LocalDateTime now = LocalDateTime.now();

        if (!delivered) {
            log.info("Event type is not delivered as a webhook, skipping. eventId={}, eventType={}", eventId, eventType);
            markProcessed(eventId, eventType, now);
            return;
        }

        Optional<MerchantWebhookSubscription> subscription = subscriptionRepository.findByMerchantIdAndActiveTrue(merchantId);

        if (subscription.isEmpty()) {
            log.debug("No active webhook subscription, skipping. eventId={}, merchantId={}", eventId, merchantId);
            markProcessed(eventId, eventType, now);
            return;
        }

        WebhookDelivery delivery = WebhookDelivery.builder()
                .id(UUID.randomUUID())
                .eventId(eventId)
                .eventType(eventType)
                .paymentId(paymentId)
                .payoutId(payoutId)
                .merchantId(merchantId)
                .subscriptionId(subscription.get().getId())
                .url(subscription.get().getUrl())
                .payload(payload.get())
                .status(WebhookDeliveryStatus.PENDING)
                .nextAttemptAt(now)
                .build();

        deliveryRepository.save(delivery);
        markProcessed(eventId, eventType, now);

        log.info(
                "Webhook delivery queued. deliveryId={}, eventId={}, eventType={}, merchantId={}",
                delivery.getId(),
                eventId,
                eventType,
                merchantId
        );
    }

    private void markProcessed(UUID eventId, String eventType, LocalDateTime now) {
        processedEventRepository.save(new ProcessedEvent(eventId, eventType, now));
    }
}
