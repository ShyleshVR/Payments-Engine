package com.shylesh.merchant_service.exception;

import java.util.UUID;

public class MerchantNotFoundException extends RuntimeException {

    public MerchantNotFoundException(UUID merchantId) {
        super("Merchant not found: " + merchantId);
    }
}
