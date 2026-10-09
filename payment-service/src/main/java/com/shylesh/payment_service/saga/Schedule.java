package com.shylesh.payment_service.saga;

/** When the worker should next look at the saga after a decision. */
public enum Schedule {

    /** Run the (new) step right away. */
    NOW,
    /** Retry the same step after a backoff (processor outcome unknown). */
    BACKOFF,
    /** A ledger command was sent: come back if its reply is overdue. */
    REPLY_TIMEOUT,
    /** MANUAL capture: come back when the authorization expires. */
    AUTHORIZATION_EXPIRY,
    /** Nothing to do until something else happens (finished or parked). */
    NONE

}
