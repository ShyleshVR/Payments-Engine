package com.shylesh.webhook_service.service;

import java.util.UUID;

public interface WebhookDeliveryService {

    void attemptDelivery(UUID deliveryId);
}
