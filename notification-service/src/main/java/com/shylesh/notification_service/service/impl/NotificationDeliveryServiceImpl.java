package com.shylesh.notification_service.service.impl;

import com.shylesh.notification_service.channel.NotificationContext;
import com.shylesh.notification_service.channel.NotificationChannelRegistry;
import com.shylesh.notification_service.channel.NotificationDeliveryException;
import com.shylesh.notification_service.channel.UnsupportedNotificationChannelException;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Delivers one notification in three steps, so no DB transaction is held open across the
 * call to the provider:
 * 1. claim  (short tx): lock the row if it is still due, lease it, commit;
 * 2. send   (no tx):    call the channel, catching every exception as a failed attempt;
 * 3. record (short tx): write the attempt and the new status.
 * A notification that exhausts its retries is marked FAILED; NotificationDeadLetterRelay
 * publishes it to the DLT only after that status has committed.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationDeliveryServiceImpl implements NotificationDeliveryService {

    /** Must comfortably exceed the slowest provider call, or a slow send could be re-claimed. */
    static final Duration LEASE = Duration.ofMinutes(2);

    private final NotificationRepository notificationRepository;
    private final NotificationDeliveryAttemptRepository deliveryAttemptRepository;
    private final NotificationChannelRegistry channelRegistry;
    private final NotificationRetryPolicy retryPolicy;
    private final TransactionTemplate transactionTemplate;
    private final MeterRegistry meterRegistry;

    /** permanent: retrying can't succeed, so the notification fails now instead of using its retries. */
    private record SendResult(boolean success, String error, boolean permanent) {

        static SendResult ok() {
            return new SendResult(true, null, false);
        }

        static SendResult failed(String error, boolean permanent) {
            return new SendResult(false, error, permanent);
        }
    }

    @Override
    public void attemptDelivery(UUID notificationId) {

        Optional<Notification> claimed = transactionTemplate.execute(status -> claim(notificationId));
        if (claimed == null || claimed.isEmpty()) {
            return;
        }

        Notification notification = claimed.get();
        int attemptNumber = notification.getAttemptCount() + 1;

        SendResult result = send(notification);

        transactionTemplate.executeWithoutResult(status -> recordOutcome(notificationId, attemptNumber, result));
    }

    private Optional<Notification> claim(UUID notificationId) {
        LocalDateTime now = LocalDateTime.now();
        return notificationRepository.lockIfDue(notificationId, now)
                .map(notification -> {
                    notification.lease(now.plus(LEASE));
                    return notification;
                });
    }

    private SendResult send(Notification notification) {
        NotificationContext context = new NotificationContext(
                notification.getId(),
                notification.getPaymentId(),
                notification.getCustomerId(),
                notification.getEventType()
        );

        try {
            channelRegistry.resolve(notification.getChannel()).send(context);
            return SendResult.ok();
        } catch (NotificationDeliveryException e) {
            return SendResult.failed(describe(e), e.isPermanent());
        } catch (UnsupportedNotificationChannelException e) {
            return SendResult.failed(describe(e), true);
        } catch (Exception e) {
            // Any other exception is still a failed, counted attempt (treated as transient), or
            // the notification would be redelivered forever without ever reaching the DLT.
            return SendResult.failed(describe(e), false);
        }
    }

    private void recordOutcome(UUID notificationId, int attemptNumber, SendResult result) {
        Notification notification = notificationRepository.findById(notificationId).orElse(null);

        if (notification == null
                || !notification.isDeliverable()
                || notification.getAttemptCount() != attemptNumber - 1) {
            // Our lease expired and another dispatcher took over this notification.
            log.warn(
                    "Discarding stale delivery outcome. notificationId={}, attempt={}",
                    notificationId,
                    attemptNumber
            );
            return;
        }

        if (result.success()) {
            recordSuccess(notification, attemptNumber);
        } else {
            recordFailure(notification, attemptNumber, result.error(), result.permanent());
        }
    }

    private void recordSuccess(Notification notification, int attemptNumber) {
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
    }

    private void recordFailure(Notification notification, int attemptNumber, String error, boolean permanent) {
        recordAttempt(notification.getId(), attemptNumber, DeliveryAttemptStatus.FAILURE, error);

        if (!permanent && retryPolicy.canRetry(attemptNumber)) {
            LocalDateTime nextAttemptAt = retryPolicy.nextAttemptAt(attemptNumber);
            notification.markRetrying(attemptNumber, nextAttemptAt, error);
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
                    error
            );
        } else {
            String reason = permanent ? "permanent" : "exhausted";
            notification.markFailed(attemptNumber, error);
            notificationRepository.save(notification);

            Counter.builder("notifications.failed")
                    .tag("channel", notification.getChannel().name())
                    .tag("eventType", notification.getEventType())
                    .tag("reason", reason)
                    .register(meterRegistry)
                    .increment();

            log.error(
                    "Notification delivery failed ({}), queued for DLT. notificationId={}, attempt={}, error={}",
                    reason,
                    notification.getId(),
                    attemptNumber,
                    error
            );
        }
    }

    private void recordAttempt(UUID notificationId, int attemptNumber, DeliveryAttemptStatus status, String errorMessage) {
        NotificationDeliveryAttempt attempt = NotificationDeliveryAttempt.builder()
                .id(UUID.randomUUID())
                .notificationId(notificationId)
                .attemptNumber(attemptNumber)
                .status(status)
                .errorMessage(Notification.truncateError(errorMessage))
                .attemptedAt(LocalDateTime.now())
                .build();

        deliveryAttemptRepository.save(attempt);
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getName() : message;
    }
}
