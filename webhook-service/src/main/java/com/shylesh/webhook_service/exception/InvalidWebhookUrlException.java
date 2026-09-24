package com.shylesh.webhook_service.exception;

public class InvalidWebhookUrlException extends RuntimeException {

    public InvalidWebhookUrlException(String url) {
        super("Webhook url must be an absolute http(s) URL: " + url);
    }
}
