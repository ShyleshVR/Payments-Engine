package com.shylesh.notification_service.channel;

/**
 * Thrown by a NotificationChannel when a send attempt fails. Treated as a transient,
 * retryable failure by the dispatcher — a channel that knows a failure is permanent
 * (e.g. an invalid recipient) is still reported this way for now; distinguishing
 * permanent from transient failures is a dispatcher-level concern for Phase 2.
 */
public class NotificationDeliveryException extends Exception {

    public NotificationDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }

    public NotificationDeliveryException(String message) {
        super(message);
    }
}
