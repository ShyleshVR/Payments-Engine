package com.shylesh.payment_service.exception;

import com.shylesh.payment_service.entity.PaymentStatus;

public class InvalidPaymentStateException extends RuntimeException {

    public InvalidPaymentStateException(PaymentStatus from, PaymentStatus to) {
        super("Cannot transition payment from " + from + " to " + to);
    }
}