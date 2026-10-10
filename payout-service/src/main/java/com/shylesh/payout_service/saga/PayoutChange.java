package com.shylesh.payout_service.saga;

/** What a saga decision does to the payout itself (and which event it publishes). */
public enum PayoutChange {

    NONE,
    /** The bank accepted the transfer. */
    IN_TRANSIT,
    /** PAYOUT_PAID */
    PAID,
    /** PAYOUT_FAILED */
    FAILED,
    /** PAYOUT_RETURNED */
    RETURNED

}
