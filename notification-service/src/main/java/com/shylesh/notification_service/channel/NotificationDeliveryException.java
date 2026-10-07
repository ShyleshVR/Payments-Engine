package com.shylesh.notification_service.channel;

/**
 * Thrown by a NotificationChannel when a send attempt fails.
 *
 * Transient by default (provider timeout, rate limit, 5xx): the notification is retried with
 * backoff. A channel that knows retrying can't help (unknown mailbox, invalid phone number,
 * content rejected) throws {@link #permanent(String)} instead, and the notification fails at
 * once and goes to the DLT rather than spending all its retries.
 */
public class NotificationDeliveryException extends Exception {

    private final boolean permanent;

    public NotificationDeliveryException(String message, Throwable cause) {
        super(message, cause);
        this.permanent = false;
    }

    public NotificationDeliveryException(String message) {
        super(message);
        this.permanent = false;
    }

    private NotificationDeliveryException(String message, boolean permanent) {
        super(message);
        this.permanent = permanent;
    }

    public static NotificationDeliveryException permanent(String message) {
        return new NotificationDeliveryException(message, true);
    }

    public boolean isPermanent() {
        return permanent;
    }
}
