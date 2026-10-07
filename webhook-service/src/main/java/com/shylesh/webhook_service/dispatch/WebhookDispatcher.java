package com.shylesh.webhook_service.dispatch;

import com.shylesh.webhook_service.config.WebhookProperties;
import com.shylesh.webhook_service.persistence.WebhookDelivery;
import com.shylesh.webhook_service.persistence.WebhookDeliveryRepository;
import com.shylesh.webhook_service.persistence.WebhookDeliveryStatus;
import com.shylesh.webhook_service.service.WebhookDeliveryService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 * Polls for due deliveries and hands them to the delivery worker pool without waiting for
 * them to finish, so a slow merchant endpoint ties up one worker and nothing else: the next
 * poll still runs on schedule and other merchants' deliveries keep flowing.
 *
 * At most maxInFlight deliveries are queued or running per instance. A delivery already in
 * flight is never submitted twice, and across instances each delivery is claimed individually
 * (SKIP LOCKED + lease), so several instances can poll the same table.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WebhookDispatcher {

    private static final List<WebhookDeliveryStatus> DISPATCHABLE_STATUSES =
            List.of(WebhookDeliveryStatus.PENDING, WebhookDeliveryStatus.RETRYING);

    private final WebhookDeliveryRepository deliveryRepository;
    private final WebhookDeliveryService deliveryService;
    private final ExecutorService webhookDeliveryExecutor;
    private final WebhookProperties properties;

    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    @Scheduled(fixedDelayString = "${webhook.delivery.poll-interval:PT5S}")
    public void dispatchDueDeliveries() {
        int maxInFlight = properties.delivery().maxInFlight();
        int capacity = maxInFlight - inFlight.size();
        if (capacity <= 0) {
            return;
        }

        List<UUID> due = deliveryRepository
                .findByStatusInAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
                        DISPATCHABLE_STATUSES,
                        LocalDateTime.now(),
                        Limit.of(maxInFlight)
                )
                .stream()
                .map(WebhookDelivery::getId)
                .filter(id -> !inFlight.contains(id))
                .limit(capacity)
                .toList();

        for (UUID deliveryId : due) {
            inFlight.add(deliveryId);
            try {
                webhookDeliveryExecutor.execute(() -> attempt(deliveryId));
            } catch (RuntimeException e) {
                inFlight.remove(deliveryId);
                log.error("Could not schedule webhook delivery {}: {}", deliveryId, e.getMessage());
            }
        }
    }

    int inFlightCount() {
        return inFlight.size();
    }

    private void attempt(UUID deliveryId) {
        try {
            deliveryService.attemptDelivery(deliveryId);
        } catch (Exception e) {
            // e.g. the database is down during claim/record: the delivery stays due (or its
            // lease expires) and is picked up by a later poll.
            log.error("Unexpected error dispatching webhook delivery {}: {}", deliveryId, e.getMessage(), e);
        } finally {
            inFlight.remove(deliveryId);
        }
    }
}
