package com.shylesh.notification_service.service.impl;

import com.shylesh.notification_service.channel.NotificationChannel;
import com.shylesh.notification_service.channel.NotificationChannelRegistry;
import com.shylesh.notification_service.channel.NotificationContext;
import com.shylesh.notification_service.channel.NotificationDeliveryException;
import com.shylesh.notification_service.dlt.NotificationDeadLetterPublisher;
import com.shylesh.notification_service.persistance.DeliveryAttemptStatus;
import com.shylesh.notification_service.persistance.Notification;
import com.shylesh.notification_service.persistance.NotificationDeliveryAttempt;
import com.shylesh.notification_service.persistance.NotificationDeliveryAttemptRepository;
import com.shylesh.notification_service.persistance.NotificationRepository;
import com.shylesh.notification_service.retry.NotificationRetryPolicy;
import com.shylesh.notification_service.service.NotificationDeliveryService;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationDeliveryServiceImpl implements NotificationDeliveryService {

    private final NotificationRepository notificationRepository;
    private final NotificationDeliveryAttemptRepository deliveryAttemptRepository;
    private final NotificationChannelRegistry channelRegistry;
    private final NotificationRetryPolicy retryPolicy;
    private final NotificationDeadLetterPublisher deadLetterPublisher;
    private final MeterRegistry meterRegistry;

    @Override
    @Transactional
    public void attemptDelivery(UUID notificationId) {

        Notification notification = notificationRepository.findById(notificationId).orElse(null);
        if (notification == null) {
            return;
        }

        int attemptNumber = notification.getAttemptCount() + 1;
        NotificationChannel channel = channelRegistry.resolve(notification.getChannel());
        NotificationContext context = new NotificationContext(
                notification.getId(),
                notification.getPaymentId(),
                notification.getCustomerId(),
                notification.getEventType()
        );

        try {
            channel.send(context);
            recordAttempt(notification.getId(), attemptNumber, DeliveryAttemptStatus.SUCCESS, null);
            notification.markSent(attemptNumber);
            notificationRepository.save(notification);

            Counter.builder("notifications.sent")
                    .tag("channel", notification.getChannel().name())
                    .tag("eventType", notification.getEventType())
                    .register(meterRegistry)
                    .increment();

            Timer.builder("notifications.delivery.latency")
                    .tag("channel", notification.getChannel().name())
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
                    .record(Duration.between(notification.getCreatedAt(), LocalDateTime.now()));

            log.info(
                    "Notification delivered. notificationId={}, channel={}, attempt={}",
                    notification.getId(),
                    notification.getChannel(),
                    attemptNumber
            );

        } catch (NotificationDeliveryException e) {
            recordAttempt(notification.getId(), attemptNumber, DeliveryAttemptStatus.FAILURE, e.getMessage());

            if (retryPolicy.canRetry(attemptNumber)) {
                LocalDateTime nextAttemptAt = retryPolicy.nextAttemptAt(attemptNumber);
                notification.markRetrying(attemptNumber, nextAttemptAt, e.getMessage());
                notificationRepository.save(notification);

                Counter.builder("notifications.retried")
                        .tag("channel", notification.getChannel().name())
                        .tag("eventType", notification.getEventType())
                        .register(meterRegistry)
                        .increment();

                log.warn(
                        "Notification delivery failed, will retry. notificationId={}, attempt={}, nextAttemptAt={}, error={}",
                        notification.getId(),
                        attemptNumber,
                        nextAttemptAt,
                        e.getMessage()
                );
            } else {
                notification.markFailed(attemptNumber, e.getMessage());
                notificationRepository.save(notification);
                deadLetterPublisher.publish(notification, e.getMessage());

                log.error(
                        "Notification delivery exhausted retries, sent to DLT. notificationId={}, attempt={}, error={}",
                        notification.getId(),
                        attemptNumber,
                        e.getMessage()
                );
            }
        }
    }

    private void recordAttempt(UUID notificationId, int attemptNumber, DeliveryAttemptStatus status, String errorMessage) {
        NotificationDeliveryAttempt attempt = NotificationDeliveryAttempt.builder()
                .id(UUID.randomUUID())
                .notificationId(notificationId)
                .attemptNumber(attemptNumber)
                .status(status)
                .errorMessage(errorMessage)
                .attemptedAt(LocalDateTime.now())
                .build();

        deliveryAttemptRepository.save(attempt);
    }
}
