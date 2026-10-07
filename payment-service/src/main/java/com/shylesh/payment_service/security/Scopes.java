package com.shylesh.payment_service.security;

/** OAuth2 scopes (issued by merchant-service) that this service enforces. */
public final class Scopes {

    /** Merchant: create, cancel and refund its own payments. */
    public static final String PAYMENTS_WRITE = "SCOPE_payments:write";
    /** Merchant: read its own payments. */
    public static final String PAYMENTS_READ = "SCOPE_payments:read";
    /** Internal operations: drive processing outcomes (process / complete / fail) on any payment. */
    public static final String PAYMENTS_OPERATE = "SCOPE_payments:operate";

    /** JWT claim carrying the merchant a token acts for. Absent on operator tokens. */
    public static final String MERCHANT_ID_CLAIM = "merchant_id";

    private Scopes() {
    }
}
