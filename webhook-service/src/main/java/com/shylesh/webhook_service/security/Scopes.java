package com.shylesh.webhook_service.security;

/** OAuth2 scopes (issued by merchant-service) that this service enforces. */
public final class Scopes {

    /** Merchant: manage its own webhook subscription and read its own delivery audit. */
    public static final String WEBHOOKS_MANAGE = "SCOPE_webhooks:manage";

    /** JWT claim carrying the merchant a token acts for. Absent on operator tokens. */
    public static final String MERCHANT_ID_CLAIM = "merchant_id";

    private Scopes() {
    }
}
