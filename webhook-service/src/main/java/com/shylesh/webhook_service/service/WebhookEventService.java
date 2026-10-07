package com.shylesh.webhook_service.service;

import com.shylesh.webhook_service.event.PaymentEvent;

public interface WebhookEventService {

    void handle(PaymentEvent event);
}
