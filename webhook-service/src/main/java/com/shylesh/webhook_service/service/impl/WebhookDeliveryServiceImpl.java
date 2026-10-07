package com.shylesh.webhook_service.service.impl;

import com.shylesh.webhook_service.config.WebhookProperties;
import com.shylesh.webhook_service.exception.InvalidWebhookUrlException;
import com.shylesh.webhook_service.http.HttpOutcome;
import com.shylesh.webhook_service.http.WebhookHttpClient;
import com.shylesh.webhook_service.persistence.DeliveryAttemptStatus;
import com.shylesh.webhook_service.persistence.MerchantWebhookSubscription;
import com.shylesh.webhook_service.persistence.MerchantWebhookSubscriptionRepository;
import com.shylesh.webhook_service.persistence.WebhookDelivery;
import com.shylesh.webhook_service.persistence.WebhookDeliveryAttempt;
import com.shylesh.webhook_service.persistence.WebhookDeliveryAttemptRepository;
import com.shylesh.webhook_service.persistence.WebhookDeliveryRepository;
import com.shylesh.webhook_service.retry.WebhookRetryPolicy;
import com.shylesh.webhook_service.security.WebhookTargetValidator;
import com.shylesh.webhook_service.service.WebhookDeliveryService;
import com.shylesh.webhook_service.signing.WebhookSigner;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Delivers one webhook in three steps, so no DB transaction is open during the HTTP call:
 * 1. claim  (short tx): lock the row if still due, check the subscription, lease, commit;
 * 2. send   (no tx):    re-check the target address, sign, POST;
 * 3. record (short tx): write the attempt and the new status.
 * Outcomes: 2xx -> DELIVERED; retryable (5xx/408/429/timeout/connection) -> RETRYING until
 * attempts run out; permanent (other 4xx, 3xx, blocked target) -> FAILED at once. FAILED
 * deliveries are dead-lettered by WebhookDeadLetterRelay after this transaction commits.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WebhookDeliveryServiceImpl implements WebhookDeliveryService {

    static final String SUBSCRIPTION_INACTIVE_REASON = "Subscription deactivated before delivery";
    static final String USER_AGENT = "PayFlow-Webhooks/1.0";

    private final WebhookDeliveryRepository deliveryRepository;
    private final WebhookDeliveryAttemptRepository attemptRepository;
    private final MerchantWebhookSubscriptionRepository subscriptionRepository;
    private final WebhookSigner signer;
    private final WebhookHttpClient httpClient;
    private final WebhookTargetValidator targetValidator;
    private final WebhookRetryPolicy retryPolicy;
    private final WebhookProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final MeterRegistry meterRegistry;

    /** What the send step needs, captured inside the claim transaction. */
    record ClaimedDelivery(UUID deliveryId, int attemptNumber, String url, String payload, String secret) {
    }

    @Override
    public void attemptDelivery(UUID deliveryId) {

        Optional<ClaimedDelivery> claimed = transactionTemplate.execute(status -> claim(deliveryId));
        if (claimed == null || claimed.isEmpty()) {
            return;
        }

        HttpOutcome outcome = send(claimed.get());

        transactionTemplate.executeWithoutResult(status -> recordOutcome(claimed.get(), outcome));
    }

    private Optional<ClaimedDelivery> claim(UUID deliveryId) {
        LocalDateTime now = LocalDateTime.now();

        Optional<WebhookDelivery> locked = deliveryRepository.lockIfDue(deliveryId, now);
        if (locked.isEmpty()) {
            return Optional.empty();
        }
        WebhookDelivery delivery = locked.get();

        MerchantWebhookSubscription subscription =
                subscriptionRepository.findById(delivery.getSubscriptionId()).orElse(null);
        if (subscription == null || !subscription.isActive()) {
            delivery.markCancelled(SUBSCRIPTION_INACTIVE_REASON);
            log.info("Webhook delivery cancelled, subscription no longer active. deliveryId={}, merchantId={}",
                    delivery.getId(), delivery.getMerchantId());
            return Optional.empty();
        }

        delivery.lease(now.plus(properties.delivery().lease()));
        return Optional.of(new ClaimedDelivery(
                delivery.getId(),
                delivery.getAttemptCount() + 1,
                delivery.getUrl(),
                delivery.getPayload(),
                subscription.getSecret()
        ));
    }

    private HttpOutcome send(ClaimedDelivery claimed) {
        try {
            URI target = targetValidator.parse(claimed.url());

            switch (targetValidator.checkTarget(target)) {
                case BLOCKED -> {
                    return HttpOutcome.permanent("Target resolves to a private, loopback or link-local address", 0);
                }
                case UNRESOLVABLE -> {
                    return HttpOutcome.retryable("Host '" + target.getHost() + "' does not resolve", 0);
                }
                case ALLOWED -> { }
            }

            byte[] body = claimed.payload().getBytes(StandardCharsets.UTF_8);
            long timestamp = Instant.now().getEpochSecond();

            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Content-Type", "application/json");
            headers.put("User-Agent", USER_AGENT);
            headers.put(WebhookSigner.TIMESTAMP_HEADER, Long.toString(timestamp));
            headers.put(WebhookSigner.SIGNATURE_HEADER, signer.sign(claimed.secret(), timestamp, body));

            return httpClient.post(target, headers, body);
        } catch (InvalidWebhookUrlException e) {
            // e.g. require-https was switched on after this http subscription was created:
            // retrying can't help.
            return HttpOutcome.permanent("Webhook URL no longer allowed: " + e.getMessage(), 0);
        } catch (Exception e) {
            // Anything unexpected is still a recorded, counted attempt, never a silent loop.
            String message = e.getMessage() == null ? e.getClass().getName() : e.getMessage();
            return HttpOutcome.retryable("Unexpected error: " + message, 0);
        }
    }

    private void recordOutcome(ClaimedDelivery claimed, HttpOutcome outcome) {
        WebhookDelivery delivery = deliveryRepository.findById(claimed.deliveryId()).orElse(null);

        if (delivery == null
                || !delivery.isDeliverable()
                || delivery.getAttemptCount() != claimed.attemptNumber() - 1) {
            // Cancelled meanwhile, or our lease expired and another dispatcher took over.
            log.warn("Discarding stale delivery outcome. deliveryId={}, attempt={}, outcome={}",
                    claimed.deliveryId(), claimed.attemptNumber(), outcome.result());
            return;
        }

        int attemptNumber = claimed.attemptNumber();
        recordAttempt(delivery.getId(), attemptNumber, outcome);

        switch (outcome.result()) {
            case SUCCESS -> {
                delivery.markDelivered(attemptNumber);
                count("webhooks.delivered", delivery.getEventType());
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
                log.info("Webhook delivered. deliveryId={}, merchantId={}, attempt={}, status={}",
                        delivery.getId(), delivery.getMerchantId(), attemptNumber, outcome.statusCode());
            }
            case RETRYABLE -> {
                if (retryPolicy.canRetry(attemptNumber)) {
                    LocalDateTime nextAttemptAt = retryPolicy.nextAttemptAt(attemptNumber);
                    delivery.markRetrying(attemptNumber, nextAttemptAt, outcome.error());
                    count("webhooks.retried", delivery.getEventType());
                    log.warn("Webhook delivery failed, will retry. deliveryId={}, attempt={}, nextAttemptAt={}, error={}",
                            delivery.getId(), attemptNumber, nextAttemptAt, outcome.error());
                } else {
                    markFailed(delivery, attemptNumber, outcome, "exhausted");
                }
            }
            case PERMANENT -> markFailed(delivery, attemptNumber, outcome, "permanent");
        }
    }

    private void markFailed(WebhookDelivery delivery, int attemptNumber, HttpOutcome outcome, String reason) {
        delivery.markFailed(attemptNumber, outcome.error());
        Counter.builder("webhooks.failed")
                .tag("eventType", delivery.getEventType())
                .tag("reason", reason)
                .register(meterRegistry)
                .increment();
        log.error("Webhook delivery failed ({}), queued for DLT. deliveryId={}, attempt={}, error={}",
                reason, delivery.getId(), attemptNumber, outcome.error());
    }

    private void recordAttempt(UUID deliveryId, int attemptNumber, HttpOutcome outcome) {
        attemptRepository.save(WebhookDeliveryAttempt.builder()
                .id(UUID.randomUUID())
                .deliveryId(deliveryId)
                .attemptNumber(attemptNumber)
                .status(outcome.result() == HttpOutcome.Result.SUCCESS
                        ? DeliveryAttemptStatus.SUCCESS
                        : DeliveryAttemptStatus.FAILURE)
                .responseCode(outcome.statusCode())
                .errorMessage(WebhookDelivery.truncateError(outcome.error()))
                .durationMs(outcome.durationMs())
                .attemptedAt(LocalDateTime.now())
                .build());
    }

    private void count(String name, String eventType) {
        Counter.builder(name)
                .tag("eventType", eventType)
                .register(meterRegistry)
                .increment();
    }
}
