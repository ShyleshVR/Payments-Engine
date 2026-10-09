package com.shylesh.payment_service.common.kafka;

/** A message that can never be processed (malformed, missing fields): dead-lettered at once. */
public class InvalidMessageException extends RuntimeException {

    public InvalidMessageException(String message) {
        super(message);
    }

    public InvalidMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
