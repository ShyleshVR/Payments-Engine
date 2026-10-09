package com.shylesh.ledger_service.config;

public final class LedgerTopics {

    /** Saga commands from payment-service (owned and created by payment-service). */
    public static final String COMMANDS = "ledger-commands";

    /** The ledger's replies to them, keyed by payment id. */
    public static final String REPLIES = "ledger-replies";
    public static final String REPLIES_DLT = REPLIES + ".DLT";

    private LedgerTopics() {
    }
}
