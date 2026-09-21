package com.shylesh.notification_service.exception;

import com.shylesh.notification_service.persistance.NotificationStatus;

import java.util.UUID;

public class InvalidNotificationStateException extends RuntimeException {

    public InvalidNotificationStateException(UUID notificationId, NotificationStatus current) {
        super("Cannot process notification " + notificationId + " in status " + current);
    }
}
