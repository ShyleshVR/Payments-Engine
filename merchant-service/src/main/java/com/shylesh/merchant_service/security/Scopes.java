package com.shylesh.merchant_service.security;

import java.util.Set;

/** OAuth2 scopes issued by this authorization server and enforced by every PayFlow API. */
public final class Scopes {

    // merchant scopes
    public static final String PAYMENTS_WRITE = "payments:write";
    public static final String PAYMENTS_READ = "payments:read";
    public static final String LEDGER_READ = "ledger:read";
    public static final String WEBHOOKS_MANAGE = "webhooks:manage";

    // operator scopes
    public static final String PAYMENTS_OPERATE = "payments:operate";
    public static final String LEDGER_ADMIN = "ledger:admin";
    public static final String MERCHANTS_ADMIN = "merchants:admin";
    /** Read-only: any payment (reconciliation). */
    public static final String PAYMENTS_AUDIT = "payments:audit";
    /** Reconciliation runs and reports. */
    public static final String RECONCILIATION_ADMIN = "reconciliation:admin";

    public static final Set<String> MERCHANT = Set.of(PAYMENTS_WRITE, PAYMENTS_READ, LEDGER_READ, WEBHOOKS_MANAGE);
    public static final Set<String> ADMIN = Set.of(MERCHANTS_ADMIN, PAYMENTS_OPERATE, LEDGER_ADMIN, RECONCILIATION_ADMIN);

    /** What a configured service client may be granted: operator-level scopes, never merchant ones. */
    public static final Set<String> SERVICE_ASSIGNABLE = Set.of(PAYMENTS_OPERATE, PAYMENTS_AUDIT, LEDGER_ADMIN, RECONCILIATION_ADMIN);

    /** JWT claim carrying the merchant a token acts for. Absent on operator tokens. */
    public static final String MERCHANT_ID_CLAIM = "merchant_id";

    private Scopes() {
    }
}
