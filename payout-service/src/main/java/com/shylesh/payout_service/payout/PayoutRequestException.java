package com.shylesh.payout_service.payout;

/**
 * The merchant's payout request can't be accepted (422): no destination, more than the payable
 * balance, or an Idempotency-Key reused for a different request.
 */
public class PayoutRequestException extends RuntimeException {

    private final String code;

    public PayoutRequestException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
