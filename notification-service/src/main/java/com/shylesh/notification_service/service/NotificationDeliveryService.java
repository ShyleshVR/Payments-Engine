package com.shylesh.notification_service.service;

import java.util.UUID;

public interface NotificationDeliveryService {

    void attemptDelivery(UUID notificationId);
}
