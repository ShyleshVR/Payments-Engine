package com.shylesh.payout_service.payout;

public enum PayoutTrigger {

    /** The daily batch: the whole payable balance. */
    BATCH,
    /** Requested by the merchant: an amount of their choice, now. */
    INSTANT

}
