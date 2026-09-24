package com.shylesh.webhook_service.dlt;

/** Kafka did not confirm a dead letter within the send timeout (or rejected it outright). */
public class DeadLetterPublishException extends Exception {

    public DeadLetterPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
