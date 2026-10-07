package com.shylesh.webhook_service.exception;

public class InvalidPaymentIdException extends RuntimeException {

    public InvalidPaymentIdException(String paymentId) {
        super("Invalid payment id: " + paymentId);
    }
}
