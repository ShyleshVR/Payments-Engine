package com.shylesh.payment_service.exception;

import java.util.UUID;

public class PaymentNotFoundException extends RuntimeException {

    public PaymentNotFoundException(String paymentId) {
        super("Payment not found: " + paymentId);
    }

    public PaymentNotFoundException(UUID paymentId) {
        super("Payment not found: " + paymentId.toString());
    }

}
