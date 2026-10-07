package com.shylesh.webhook_service.dlt;

public class DeadLetterPublishException extends RuntimeException {

    public DeadLetterPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
