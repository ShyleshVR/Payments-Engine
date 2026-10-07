package com.shylesh.merchant_service.exception;

/** The request is valid but conflicts with the merchant's current state (409). */
public class MerchantConflictException extends RuntimeException {

    public MerchantConflictException(String message) {
        super(message);
    }
}
