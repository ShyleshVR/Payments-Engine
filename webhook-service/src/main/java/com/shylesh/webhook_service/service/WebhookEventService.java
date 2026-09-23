package com.shylesh.webhook_service.service;

import com.shylesh.webhook_service.event.EventEnvelope;

public interface WebhookEventService {

    void handle(EventEnvelope envelope);
}
