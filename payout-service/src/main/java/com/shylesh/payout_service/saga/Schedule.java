package com.shylesh.payout_service.saga;

/** When the worker should next look at the saga after a decision. */
public enum Schedule {

    /** Run the (new) step right away. */
    NOW,
    /** Retry the same bank call after a backoff (outcome unknown). */
    BACKOFF,
    /** Check the in-transit transfer again later. */
    TRANSIT_POLL,
    /** Check the paid transfer for a return later. */
    RETURN_CHECK,
    /** A ledger command was sent: come back if its reply is overdue. */
    REPLY_TIMEOUT,
    /** Nothing to do until something else happens (finished or parked). */
    NONE

}
