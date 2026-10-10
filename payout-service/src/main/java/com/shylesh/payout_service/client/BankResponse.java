package com.shylesh.payout_service.client;

/**
 * What a bank call came to.
 *
 * @param transferId     the transfer, for OK
 * @param transferStatus PENDING, PAID, FAILED or RETURNED, for OK
 * @param code           the bank's decline or failure code (e.g. invalid_account, account_closed),
 *                       or our own for UNKNOWN outcomes
 */
public record BankResponse(Outcome outcome, int httpStatus, String transferId, String transferStatus, String code,
                           String message) {

    public enum Outcome {
        /** The bank answered (2xx) with the transfer's current state. */
        OK,
        /** The bank won't take the transfer (402). Final; nothing was sent. */
        DECLINED,
        /** The bank refused the request (other 4xx). Final; a bug or a misconfiguration. */
        REJECTED,
        /** No answer we can trust: timeout, connection failure, 5xx. Retry (same key). */
        UNKNOWN
    }

    public static BankResponse unknown(String code, String message) {
        return new BankResponse(Outcome.UNKNOWN, 0, null, null, code, message);
    }

    public String describe() {
        return outcome + (httpStatus > 0 ? " " + httpStatus : "")
                + (transferStatus != null ? " " + transferStatus : "")
                + (code != null ? " " + code : "")
                + (message != null ? ": " + message : "");
    }
}
