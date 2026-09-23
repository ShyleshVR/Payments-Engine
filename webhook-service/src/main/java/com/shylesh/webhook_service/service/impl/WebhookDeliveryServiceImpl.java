package com.shylesh.webhook_service.service.impl;

import com.shylesh.webhook_service.dlt.WebhookDeadLetterPublisher;
import com.shylesh.webhook_service.http.WebhookDeliveryException;
import com.shylesh.webhook_service.http.WebhookHttpClient;
import com.shylesh.webhook_service.persistence.DeliveryAttemptStatus;
import com.shylesh.webhook_service.persistence.MerchantWebhookSubscription;
import com.shylesh.webhook_service.persistence.MerchantWebhookSubscriptionRepository;
import com.shylesh.webhook_service.persistence.WebhookDelivery;
import com.shylesh.webhook_service.persistence.WebhookDeliveryAttempt;
import com.shylesh.webhook_service.persistence.WebhookDeliveryAttemptRepository;
import com.shylesh.webhook_service.persistence.WebhookDeliveryRepository;
import com.shylesh.webhook_service.persistence.WebhookDeliveryStatus;
import com.shylesh.webhook_service.retry.WebhookRetryPolicy;
import com.shylesh.webhook_service.service.WebhookDeliveryService;
import com.shylesh.webhook_service.signing.WebhookSigner;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class WebhookDeliveryServiceImpl implements WebhookDeliveryService {

    static final String SUBSCRIPTION_INACTIVE_REASON = "Subscription deactivated before delivery";

    private static final int MAX_ERROR_LENGTH = 1000;

    private final WebhookDeliveryRepository deliveryRepository;
    private final WebhookDeliveryAttemptRepository attemptRepository;
    private final MerchantWebhookSubscriptionRepository subscriptionRepository;
    private final WebhookSigner signer;
    private final WebhookHttpClient httpClient;
    private final WebhookRetryPolicy retryPolicy;
    private final WebhookDeadLetterPublisher deadLetterPublisher;
    private final MeterRegistry meterRegistry;

    /*
     * Note: the HTTP call runs inside this transaction, same as notification-service's
     * channel send. With the dispatcher processing one delivery at a time this holds at most
     * one DB connection for up to connect+read timeout; revisit if dispatch is parallelised.
     */
    @Override
    @Transactional
    public void attemptDelivery(UUID deliveryId) {

        WebhookDelivery delivery = deliveryRepository.findById(deliveryId).orElse(null);
        if (delivery == null || !isDeliverable(delivery)) {
            return;
        }

        MerchantWebhookSubscription subscription =
                subscriptionRepository.findById(delivery.getSubscriptionId()).orElse(null);

        if (subscription == null || !subscription.isActive()) {
            delivery.markCancelled(SUBSCRIPTION_INACTIVE_REASON);
            deliveryRepository.save(delivery);
            log.info(
                    "Webhook delivery cancelled, subscription no longer active. deliveryId={}, merchantId={}",
                    delivery.getId(),
                    delivery.getMerchantId()
            );
            return;
        }

        int attemptNumber = delivery.getAttemptCount() + 1;
        byte[] body = delivery.getPayload().getBytes(StandardCharsets.UTF_8);
        String signature = signer.sign(subscription.getSecret(), body);

        try {
            int responseCode = httpClient.post(delivery.getUrl(), signature, body);

            recordAttempt(delivery.getId(), attemptNumber, DeliveryAttemptStatus.SUCCESS, responseCode, null);
            delivery.markDelivered(attemptNumber);
            deliveryRepository.save(delivery);

            Counter.builder("webhooks.delivered")
                    .tag("eventType", delivery.getEventType())
                    .register(meterRegistry)
                    .increment();

            Timer.builder("webhooks.delivery.latency")
                    .serviceLevelObjectives(
                            Duration.ofMillis(100),
                            Duration.ofSeconds(1),
                            Duration.ofSeconds(5),
                            Duration.ofSeconds(10),
                            Duration.ofSeconds(30),
                            Duration.ofMinutes(1),
                            Duration.ofMinutes(5),
                            Duration.ofMinutes(10)
                    )
                    .register(meterRegistry)
                    .record(Duration.between(delivery.getCreatedAt(), LocalDateTime.now()));

            log.info(
                    "Webhook delivered. deliveryId={}, merchantId={}, attempt={}, responseCode={}",
                    delivery.getId(),
                    delivery.getMerchantId(),
                    attemptNumber,
                    responseCode
            );

        } catch (WebhookDeliveryException e) {
            String error = truncate(e.getMessage());
            recordAttempt(delivery.getId(), attemptNumber, DeliveryAttemptStatus.FAILURE, e.getResponseCode(), error);

            if (retryPolicy.canRetry(attemptNumber)) {
                LocalDateTime nextAttemptAt = retryPolicy.nextAttemptAt(attemptNumber);
                delivery.markRetrying(attemptNumber, nextAttemptAt, error);
                deliveryRepository.save(delivery);

                Counter.builder("webhooks.retried")
                        .tag("eventType", delivery.getEventType())
                        .register(meterRegistry)
                        .increment();

                log.warn(
                        "Webhook delivery failed, will retry. deliveryId={}, attempt={}, nextAttemptAt={}, error={}",
                        delivery.getId(),
                        attemptNumber,
                        nextAttemptAt,
                        error
                );
            } else {
                delivery.markFailed(attemptNumber, error);
                deliveryRepository.save(delivery);
                deadLetterPublisher.publish(delivery, error);

                log.error(
                        "Webhook delivery exhausted retries, sent to DLT. deliveryId={}, attempt={}, error={}",
                        delivery.getId(),
                        attemptNumber,
                        error
                );
            }
        }
    }

    private static boolean isDeliverable(WebhookDelivery delivery) {
        return delivery.getStatus() == WebhookDeliveryStatus.PENDING
                || delivery.getStatus() == WebhookDeliveryStatus.RETRYING;
    }

    private static String truncate(String message) {
        if (message == null || message.length() <= MAX_ERROR_LENGTH) {
            return message;
        }
        return message.substring(0, MAX_ERROR_LENGTH);
    }

    private void recordAttempt(UUID deliveryId, int attemptNumber, DeliveryAttemptStatus status,
                               Integer responseCode, String errorMessage) {
        WebhookDeliveryAttempt attempt = WebhookDeliveryAttempt.builder()
                .id(UUID.randomUUID())
                .deliveryId(deliveryId)
                .attemptNumber(attemptNumber)
                .status(status)
                .responseCode(responseCode)
                .errorMessage(errorMessage)
                .attemptedAt(LocalDateTime.now())
                .build();

        attemptRepository.save(attempt);
    }
}
