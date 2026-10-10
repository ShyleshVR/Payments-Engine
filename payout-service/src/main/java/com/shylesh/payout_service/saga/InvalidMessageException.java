package com.shylesh.payout_service.saga;

/** A Kafka message that can never be processed: dead-lettered at once, never retried. */
public class InvalidMessageException extends RuntimeException {

    public InvalidMessageException(String message) {
        super(message);
    }

    public InvalidMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
