package com.shylesh.payout_service.security;

/** Authorities checked by @PreAuthorize (scope claim values, prefixed SCOPE_ by Spring Security). */
public final class Scopes {

    /** Merchant: its own payouts and payable balance. */
    public static final String PAYOUTS_READ = "SCOPE_payouts:read";
    /** Merchant: its payout destination and instant payouts. */
    public static final String PAYOUTS_WRITE = "SCOPE_payouts:write";
    /** Operator: any payout, batches, parked sagas. */
    public static final String PAYOUTS_OPERATE = "SCOPE_payouts:operate";
    /** Read-only, any payout (reconciliation). */
    public static final String PAYOUTS_AUDIT = "SCOPE_payouts:audit";

    /** JWT claim carrying the merchant a token acts for. Absent on operator tokens. */
    public static final String MERCHANT_ID_CLAIM = "merchant_id";

    private Scopes() {
    }
}
