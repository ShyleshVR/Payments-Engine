package com.shylesh.ledger_service.config;

public final class LedgerTopics {

    /** Saga commands from payment-service and payout-service (created by both orchestrators). */
    public static final String COMMANDS = "ledger-commands";

    /** The ledger's replies to payment and refund commands, keyed by payment id. */
    public static final String REPLIES = "ledger-replies";
    public static final String REPLIES_DLT = REPLIES + ".DLT";

    /** The ledger's replies to payout commands, keyed by payout id (consumed by payout-service). */
    public static final String PAYOUT_REPLIES = "payout-ledger-replies";
    public static final String PAYOUT_REPLIES_DLT = PAYOUT_REPLIES + ".DLT";

    private LedgerTopics() {
    }
}
