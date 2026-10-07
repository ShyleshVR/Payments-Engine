package com.shylesh.notification_service.channel;

import com.shylesh.notification_service.persistance.NotificationChannelType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Stand-in email provider: logs what would be sent instead of calling a real SMTP/API
 * provider. Swap the body of send() for a real client later — nothing outside this class
 * needs to change, since callers only ever depend on NotificationChannel.
 *
 * notification.email.stub.simulate-failure makes the stub fail like a real provider can, to
 * exercise the retry/DLT path end to end: DELIVERY_EXCEPTION is a transient provider error,
 * PERMANENT_DELIVERY_EXCEPTION one that retrying can't fix (fails at once),
 * RUNTIME_EXCEPTION an unexpected one carrying an oversized message (as when a provider
 * returns an HTML error page). Defaults to NONE.
 */
@Slf4j
@Component
public class EmailNotificationChannel implements NotificationChannel {

    enum SimulatedFailure { NONE, DELIVERY_EXCEPTION, PERMANENT_DELIVERY_EXCEPTION, RUNTIME_EXCEPTION }

    private final SimulatedFailure simulatedFailure;

    public EmailNotificationChannel(
            @Value("${notification.email.stub.simulate-failure:NONE}") SimulatedFailure simulatedFailure) {
        this.simulatedFailure = simulatedFailure;
    }

    @Override
    public NotificationChannelType getType() {
        return NotificationChannelType.EMAIL;
    }

    @Override
    public void send(NotificationContext context) throws NotificationDeliveryException {
        switch (simulatedFailure) {
            case DELIVERY_EXCEPTION -> throw new NotificationDeliveryException("Simulated provider error: 503 Service Unavailable");
            case PERMANENT_DELIVERY_EXCEPTION -> throw NotificationDeliveryException.permanent("Simulated provider error: 550 mailbox does not exist");
            case RUNTIME_EXCEPTION -> throw new IllegalStateException("Simulated unexpected provider response: " + "<html>".repeat(400));
            case NONE -> { }
        }

        log.info(
                "Sending EMAIL notification. notificationId={}, paymentId={}, customerId={}, eventType={}",
                context.notificationId(),
                context.paymentId(),
                context.customerId(),
                context.eventType()
        );
    }
}
