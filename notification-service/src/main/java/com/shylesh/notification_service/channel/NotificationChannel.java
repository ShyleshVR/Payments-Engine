package com.shylesh.notification_service.channel;

import com.shylesh.notification_service.persistance.NotificationChannelType;

public interface NotificationChannel {

    NotificationChannelType getType();

    void send(NotificationContext context) throws NotificationDeliveryException;
}
