package com.shylesh.notification_service.channel;

import com.shylesh.notification_service.persistance.NotificationChannelType;

/** No NotificationChannel implementation exists for the type: retrying can't fix that. */
public class UnsupportedNotificationChannelException extends RuntimeException {

    public UnsupportedNotificationChannelException(NotificationChannelType type) {
        super("No NotificationChannel registered for type " + type);
    }
}
