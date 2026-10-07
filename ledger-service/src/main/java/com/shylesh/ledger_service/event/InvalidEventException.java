package com.shylesh.ledger_service.event;

/**
 * The message can never be processed as-is (malformed JSON, missing required fields).
 * The Kafka error handler sends it straight to the DLT instead of retrying.
 */
public class InvalidEventException extends RuntimeException {

    public InvalidEventException(String message) {
        super(message);
    }

    public InvalidEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
