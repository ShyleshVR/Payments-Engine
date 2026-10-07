package com.shylesh.ledger_service.security;

/** OAuth2 scopes (issued by merchant-service) that this service enforces. */
public final class Scopes {

    /** Merchant: its own balance and its own payments' transactions. */
    public static final String LEDGER_READ = "SCOPE_ledger:read";
    /** Operator: platform clearing balance, any merchant's balance and transactions. */
    public static final String LEDGER_ADMIN = "SCOPE_ledger:admin";

    /** JWT claim carrying the merchant a token acts for. Absent on operator tokens. */
    public static final String MERCHANT_ID_CLAIM = "merchant_id";

    private Scopes() {
    }
}
