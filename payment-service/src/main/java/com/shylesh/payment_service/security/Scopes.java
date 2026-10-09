package com.shylesh.payment_service.security;

/** OAuth2 scopes (issued by merchant-service) that this service enforces. */
public final class Scopes {

    /** Merchant: create, cancel and refund its own payments. */
    public static final String PAYMENTS_WRITE = "SCOPE_payments:write";
    /** Merchant: read its own payments. */
    public static final String PAYMENTS_READ = "SCOPE_payments:read";
    /** Operators: inspect and resume any payment's saga. */
    public static final String PAYMENTS_OPERATE = "SCOPE_payments:operate";
    /** Read-only access to any payment (the daily reconciliation). */
    public static final String PAYMENTS_AUDIT = "SCOPE_payments:audit";

    /** JWT claim carrying the merchant a token acts for. Absent on operator tokens. */
    public static final String MERCHANT_ID_CLAIM = "merchant_id";

    private Scopes() {
    }
}
