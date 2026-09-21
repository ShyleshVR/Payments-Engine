package com.shylesh.notification_service.dispatch;

import com.shylesh.notification_service.persistance.Notification;
import com.shylesh.notification_service.persistance.NotificationRepository;
import com.shylesh.notification_service.persistance.NotificationStatus;
import com.shylesh.notification_service.service.NotificationDeliveryService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Mirrors payment-service's OutboxPublisher: polls for work due now and processes each item
 * independently, so a failure delivering one notification never blocks the others in the batch.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationDispatcher {

    private static final List<NotificationStatus> DISPATCHABLE_STATUSES =
            List.of(NotificationStatus.PENDING, NotificationStatus.RETRYING);

    private final NotificationRepository notificationRepository;
    private final NotificationDeliveryService notificationDeliveryService;

    @Scheduled(fixedDelay = 5000)
    public void dispatchDueNotifications() {

        List<Notification> due = notificationRepository
                .findTop100ByStatusInAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
                        DISPATCHABLE_STATUSES,
                        LocalDateTime.now()
                );

        for (Notification notification : due) {
            try {
                notificationDeliveryService.attemptDelivery(notification.getId());
            } catch (Exception e) {
                log.error(
                        "Unexpected error dispatching notification {}: {}",
                        notification.getId(),
                        e.getMessage(),
                        e
                );
            }
        }
    }
}
