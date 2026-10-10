package com.shylesh.payout_service.config;

public final class Topics {

    /** Payout events (created, paid, failed, returned), keyed by payout id; webhook-service consumes them. */
    public static final String PAYOUT_EVENTS = "payout-events";
    public static final String PAYOUT_EVENTS_DLT = PAYOUT_EVENTS + ".DLT";

    /** Commands to the ledger, shared with payment-service's saga (keyed by payout id here). */
    public static final String LEDGER_COMMANDS = "ledger-commands";

    /** The ledger's replies to payout commands (created by ledger-service). */
    public static final String PAYOUT_LEDGER_REPLIES = "payout-ledger-replies";

    private Topics() {
    }
}
