package com.shylesh.webhook_service.exception;

import java.util.UUID;

public class SubscriptionNotFoundException extends RuntimeException {

    public SubscriptionNotFoundException(UUID merchantId) {
        super("No active webhook subscription for merchant " + merchantId);
    }
}
