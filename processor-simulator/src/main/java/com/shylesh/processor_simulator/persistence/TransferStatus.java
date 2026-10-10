package com.shylesh.processor_simulator.persistence;

public enum TransferStatus {

    /** Accepted, on its way to the merchant's bank. */
    PENDING,
    /** The money arrived. It can still be RETURNED by the receiving bank. */
    PAID,
    /** The receiving bank refused it; no money moved. */
    FAILED,
    /** Paid, then sent back by the receiving bank. */
    RETURNED

}
