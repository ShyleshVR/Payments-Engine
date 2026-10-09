package com.shylesh.ledger_service.command;

/** A message that can never be processed (malformed, missing fields): dead-lettered at once. */
public class InvalidCommandException extends RuntimeException {

    public InvalidCommandException(String message) {
        super(message);
    }

    public InvalidCommandException(String message, Throwable cause) {
        super(message, cause);
    }
}
