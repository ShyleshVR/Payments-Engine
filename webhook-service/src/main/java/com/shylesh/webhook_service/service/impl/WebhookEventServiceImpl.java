package com.shylesh.webhook_service.service.impl;

import com.shylesh.webhook_service.event.PaymentEvent;
import com.shylesh.webhook_service.event.PaymentEventType;
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
 * Turns a payment event into a PENDING delivery for the merchant's active subscription. The
 * delivery row and the processed_events marker commit together, so an event is either fully
 * accepted or redelivered by Kafka and handled again.
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

        if (processedEventRepository.existsById(event.eventId())) {
            log.info("Skipping already-processed event. eventId={}, eventType={}", event.eventId(), event.eventType());
            return;
        }

        LocalDateTime now = LocalDateTime.now();

        if (PaymentEventType.parse(event.eventType()).isEmpty()) {
            log.info("Event type is not delivered as a webhook, skipping. eventId={}, eventType={}",
                    event.eventId(), event.eventType());
            markProcessed(event, now);
            return;
        }

        UUID merchantId = event.data().getMerchantId();
        Optional<MerchantWebhookSubscription> subscription = subscriptionRepository.findByMerchantIdAndActiveTrue(merchantId);

        if (subscription.isEmpty()) {
            log.debug("No active webhook subscription, skipping. eventId={}, merchantId={}", event.eventId(), merchantId);
            markProcessed(event, now);
            return;
        }

        WebhookDelivery delivery = WebhookDelivery.builder()
                .id(UUID.randomUUID())
                .eventId(event.eventId())
                .eventType(event.eventType())
                .paymentId(event.data().getPaymentId())
                .merchantId(merchantId)
                .subscriptionId(subscription.get().getId())
                .url(subscription.get().getUrl())
                .payload(payloadFactory.render(payloadFactory.create(event)))
                .status(WebhookDeliveryStatus.PENDING)
                .nextAttemptAt(now)
                .build();

        deliveryRepository.save(delivery);
        markProcessed(event, now);

        log.info(
                "Webhook delivery queued. deliveryId={}, eventId={}, eventType={}, merchantId={}",
                delivery.getId(),
                event.eventId(),
                event.eventType(),
                merchantId
        );
    }

    private void markProcessed(PaymentEvent event, LocalDateTime now) {
        processedEventRepository.save(new ProcessedEvent(event.eventId(), event.eventType(), now));
    }
}
