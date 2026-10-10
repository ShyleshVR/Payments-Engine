package com.shylesh.payout_service.payout;

import java.util.UUID;

/** No such payout, or not the caller's: both are 404, so ids can't be probed. */
public class PayoutNotFoundException extends RuntimeException {

    public PayoutNotFoundException(UUID payoutId) {
        super("Payout not found: " + Payout.PUBLIC_ID_PREFIX + payoutId);
    }

    public PayoutNotFoundException(String message) {
        super(message);
    }
}
