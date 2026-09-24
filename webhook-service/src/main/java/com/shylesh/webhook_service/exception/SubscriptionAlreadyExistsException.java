package com.shylesh.webhook_service.exception;

import java.util.UUID;

public class SubscriptionAlreadyExistsException extends RuntimeException {

    public SubscriptionAlreadyExistsException(UUID merchantId) {
        super("Merchant " + merchantId + " already has an active webhook subscription; delete it before creating a new one");
    }
}
