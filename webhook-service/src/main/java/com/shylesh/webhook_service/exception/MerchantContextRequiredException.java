package com.shylesh.webhook_service.exception;

/** The endpoint acts on behalf of a merchant, but the token isn't bound to one (e.g. an operator token). */
public class MerchantContextRequiredException extends RuntimeException {

    public MerchantContextRequiredException() {
        super("This endpoint requires a merchant token");
    }
}
