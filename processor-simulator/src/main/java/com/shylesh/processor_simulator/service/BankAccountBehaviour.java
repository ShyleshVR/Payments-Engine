package com.shylesh.processor_simulator.service;

import java.util.Arrays;
import java.util.Optional;

/**
 * Test bank accounts with deterministic outcomes, like the test cards: every way a payout can
 * end is available on demand. Any other account is unknown to the bank and rejected.
 */
public enum BankAccountBehaviour {

    /** Accepted, then paid. */
    OK("ba_test_ok"),
    /** Rejected at once: the bank can't route to this account. */
    INVALID("ba_test_invalid"),
    /** Accepted, then failed by the receiving bank (account closed); no money moved. */
    CLOSED("ba_test_closed"),
    /** Accepted and paid, then sent back by the receiving bank (account frozen). */
    RETURNED("ba_test_returned"),
    /** Accepted, but stays in transit far longer than usual before it is paid. */
    SLOW("ba_test_slow");

    public static final String INVALID_ACCOUNT = "invalid_account";
    public static final String ACCOUNT_CLOSED = "account_closed";
    public static final String ACCOUNT_FROZEN = "account_frozen";

    private final String bankAccount;

    BankAccountBehaviour(String bankAccount) {
        this.bankAccount = bankAccount;
    }

    public static Optional<BankAccountBehaviour> of(String bankAccount) {
        return Arrays.stream(values()).filter(b -> b.bankAccount.equals(bankAccount)).findFirst();
    }

    public String bankAccount() {
        return bankAccount;
    }
}
