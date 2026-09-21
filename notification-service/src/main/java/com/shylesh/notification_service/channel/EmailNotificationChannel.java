package com.shylesh.notification_service.channel;

import com.shylesh.notification_service.persistance.NotificationChannelType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Stand-in email provider: logs what would be sent instead of calling a real SMTP/API
 * provider. Swap the body of send() for a real client later — nothing outside this class
 * needs to change, since callers only ever depend on NotificationChannel.
 */
@Slf4j
@Component
public class EmailNotificationChannel implements NotificationChannel {

    @Override
    public NotificationChannelType getType() {
        return NotificationChannelType.EMAIL;
    }

    @Override
    public void send(NotificationContext context) {
        log.info(
                "Sending EMAIL notification. notificationId={}, paymentId={}, customerId={}, eventType={}",
                context.notificationId(),
                context.paymentId(),
                context.customerId(),
                context.eventType()
        );
    }
}
