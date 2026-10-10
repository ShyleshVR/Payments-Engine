package com.shylesh.webhook_service.service;

import com.shylesh.webhook_service.event.PaymentEvent;
import com.shylesh.webhook_service.event.PayoutEvent;

public interface WebhookEventService {

    void handle(PaymentEvent event);

    void handle(PayoutEvent event);
}
