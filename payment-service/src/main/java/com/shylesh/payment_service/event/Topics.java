package com.shylesh.payment_service.event;

public final class Topics {

    public static final String PAYMENT_CREATED = "payment-created";

    /**
     * Where consumers dead-letter payment events. Needs at least as many partitions as
     * PAYMENT_CREATED: the recoverer writes to the same partition number.
     */
    public static final String PAYMENT_CREATED_DLT = PAYMENT_CREATED + ".DLT";

    /** Saga commands to ledger-service, keyed by payment id. */
    public static final String LEDGER_COMMANDS = "ledger-commands";
    public static final String LEDGER_COMMANDS_DLT = LEDGER_COMMANDS + ".DLT";

    /** ledger-service's replies (topic owned by ledger-service). */
    public static final String LEDGER_REPLIES = "ledger-replies";
    public static final String LEDGER_REPLIES_DLT = LEDGER_REPLIES + ".DLT";

    private Topics() {}
}