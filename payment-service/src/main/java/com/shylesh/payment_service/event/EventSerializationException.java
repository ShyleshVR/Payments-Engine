package com.shylesh.payment_service.event;

/**
 * The event can't be turned into a Kafka record. Retrying won't help, so the outbox parks it
 * instead of backing off.
 */
public class EventSerializationException extends RuntimeException {

    public EventSerializationException(String message, Throwable cause) {
        super(message, cause);
    }
}
