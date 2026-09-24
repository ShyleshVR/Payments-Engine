package com.shylesh.webhook_service.dispatch;

import com.shylesh.webhook_service.persistence.WebhookDelivery;
import com.shylesh.webhook_service.persistence.WebhookDeliveryRepository;
import com.shylesh.webhook_service.persistence.WebhookDeliveryStatus;
import com.shylesh.webhook_service.service.WebhookDeliveryService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Same shape as NotificationDispatcher: polls for deliveries due now and processes each one
 * independently, so one merchant's failing endpoint never blocks delivery to the others.
 *
 * Single-instance assumption: with more than one replica, two pollers could pick up the
 * same row. SKIP LOCKED / ShedLock is planned for the Kubernetes phase.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WebhookDispatcher {

    private static final List<WebhookDeliveryStatus> DISPATCHABLE_STATUSES =
            List.of(WebhookDeliveryStatus.PENDING, WebhookDeliveryStatus.RETRYING);

    private final WebhookDeliveryRepository deliveryRepository;
    private final WebhookDeliveryService deliveryService;

    @Scheduled(fixedDelay = 5000)
    public void dispatchDueDeliveries() {

        List<WebhookDelivery> due = deliveryRepository
                .findTop100ByStatusInAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
                        DISPATCHABLE_STATUSES,
                        LocalDateTime.now()
                );

        for (WebhookDelivery delivery : due) {
            try {
                deliveryService.attemptDelivery(delivery.getId());
            } catch (Exception e) {
                log.error(
                        "Unexpected error dispatching webhook delivery {}: {}",
                        delivery.getId(),
                        e.getMessage(),
                        e
                );
            }
        }
    }
}
