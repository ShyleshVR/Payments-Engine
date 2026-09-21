package com.shylesh.notification_service.service;

import com.shylesh.notification_service.event.EventEnvelope;

public interface NotificationEventService {

    void handle(EventEnvelope envelope);
}
