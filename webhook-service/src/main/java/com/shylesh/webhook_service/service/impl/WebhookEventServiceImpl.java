package com.shylesh.webhook_service.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shylesh.webhook_service.event.EventEnvelope;
import com.shylesh.webhook_service.event.PaymentEventData;
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
 * Turns a payment event into (at most) one pending webhook delivery. Only records the
 * work — the HTTP call happens later in WebhookDispatcher, so a slow or down merchant
 * endpoint never holds up Kafka consumption.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WebhookEventServiceImpl implements WebhookEventService {

    private final ProcessedEventRepository processedEventRepository;
    private final MerchantWebhookSubscriptionRepository subscriptionRepository;
    private final WebhookDeliveryRepository deliveryRepository;
    private final WebhookPayloadFactory payloadFactory;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public void handle(EventEnvelope envelope) {

        if (processedEventRepository.existsById(envelope.getEventId())) {
            log.info(
                    "Skipping already-processed event. eventId={}, eventType={}",
                    envelope.getEventId(),
                    envelope.getEventType()
            );
            return;
        }

        if (envelope.getData() == null || envelope.getData().isNull()) {
            throw new IllegalArgumentException(
                    "Event envelope missing data payload. eventId=" + envelope.getEventId()
                            + ", eventType=" + envelope.getEventType()
            );
        }

        PaymentEventData data = objectMapper.convertValue(envelope.getData(), PaymentEventData.class);
        LocalDateTime now = LocalDateTime.now();

        Optional<MerchantWebhookSubscription> subscription = data.getMerchantId() == null
                ? Optional.empty()
                : subscriptionRepository.findByMerchantIdAndActiveTrue(data.getMerchantId());

        if (subscription.isPresent()) {
            MerchantWebhookSubscription sub = subscription.get();

            WebhookDelivery delivery = WebhookDelivery.builder()
                    .id(UUID.randomUUID())
                    .eventId(envelope.getEventId())
                    .eventType(envelope.getEventType())
                    .paymentId(data.getPaymentId())
                    .merchantId(sub.getMerchantId())
                    .subscriptionId(sub.getId())
                    .url(sub.getUrl())
                    .payload(payloadFactory.render(payloadFactory.create(envelope, data)))
                    .status(WebhookDeliveryStatus.PENDING)
                    .nextAttemptAt(now)
                    .build();

            deliveryRepository.save(delivery);
        }

        processedEventRepository.save(
                new ProcessedEvent(envelope.getEventId(), envelope.getEventType(), now)
        );

        log.info(
                "Processed event. eventId={}, eventType={}, merchantId={}, deliveryCreated={}",
                envelope.getEventId(),
                envelope.getEventType(),
                data.getMerchantId(),
                subscription.isPresent()
        );
    }
}
