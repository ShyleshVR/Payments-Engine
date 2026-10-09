package com.shylesh.payment_service.processor;

/**
 * What a processor call came to.
 *
 * @param code processor decline/error code (e.g. do_not_honor), or our own for UNKNOWN outcomes
 * @param id   the authorization or refund id, for SUCCEEDED
 */
public record ProcessorResponse(Outcome outcome, int httpStatus, String code, String id, String message) {

    public enum Outcome {
        /** Done (2xx). */
        SUCCEEDED,
        /** A business "no" from the issuer or processor (402). Final. */
        DECLINED,
        /** The processor refused the request (other 4xx, e.g. already captured). Final. */
        REJECTED,
        /** No answer we can trust: timeout, connection failure, 5xx, circuit open. Retry with the same key. */
        UNKNOWN
    }

    public static ProcessorResponse unknown(String code, String message) {
        return new ProcessorResponse(Outcome.UNKNOWN, 0, code, null, message);
    }

    public String describe() {
        return outcome + (httpStatus > 0 ? " " + httpStatus : "") + (code != null ? " " + code : "")
                + (message != null ? ": " + message : "");
    }
}
