package com.shylesh.payment_service.exception;

public class InvalidIdentifierException extends IllegalArgumentException {

    public InvalidIdentifierException(String message) {
        super("Invalid identifier: " + message);
    }
    
}
