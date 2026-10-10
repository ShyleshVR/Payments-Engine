package com.shylesh.payout_service.payout;

/** What the merchant sees of a payout. */
public enum PayoutStatus {

    /** Being held in the ledger and sent to the bank. */
    PENDING,
    /** Accepted by the bank, on its way. */
    IN_TRANSIT,
    /** Arrived at the merchant's bank. Can still be RETURNED for a while. */
    PAID,
    /** Never paid: the payable balance didn't cover it, or the bank rejected or failed it. The amount is back in the balance. */
    FAILED,
    /** Paid, then sent back by the merchant's bank. The amount is back in the balance. */
    RETURNED

}
