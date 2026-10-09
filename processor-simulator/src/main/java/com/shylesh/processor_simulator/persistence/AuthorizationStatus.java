package com.shylesh.processor_simulator.persistence;

public enum AuthorizationStatus {

    /** Funds reserved on the card; can be captured or voided. */
    AUTHORIZED,
    /** Charged; can be refunded. */
    CAPTURED,
    /** Reservation released; nothing was charged. */
    VOIDED

}
