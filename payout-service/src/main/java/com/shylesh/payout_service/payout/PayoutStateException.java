package com.shylesh.payout_service.payout;

/** The request is valid but can't be done in the current state (409). */
public class PayoutStateException extends RuntimeException {

    public PayoutStateException(String message) {
        super(message);
    }
}
