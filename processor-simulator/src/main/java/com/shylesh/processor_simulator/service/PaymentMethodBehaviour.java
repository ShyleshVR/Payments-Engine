package com.shylesh.processor_simulator.service;

import java.util.Arrays;
import java.util.Optional;

/**
 * Test payment methods with deterministic outcomes, like a real processor's test cards. Every
 * failure mode a caller has to handle can be produced on demand.
 */
public enum PaymentMethodBehaviour {

    /** Every step succeeds. */
    VISA("pm_card_visa", null, null, null),
    /** Authorization declined by the issuer. */
    DECLINED("pm_card_declined", "do_not_honor", null, null),
    INSUFFICIENT_FUNDS("pm_card_insufficient_funds", "insufficient_funds", null, null),
    /** Authorized, but the capture is declined: the authorization has to be voided. */
    CAPTURE_FAILS("pm_card_capture_fails", null, "capture_declined", null),
    /** Payment succeeds; refunds of it are declined. */
    REFUND_FAILS("pm_card_refund_fails", null, null, "refund_declined"),
    /** The first authorization answer arrives after the caller's timeout, although it succeeded. */
    TIMEOUT_ONCE("pm_card_timeout_once", null, null, null),
    /** The first calls of every operation fail with 503 (see processor.flaky-failures). */
    FLAKY("pm_card_flaky", null, null, null);

    private final String paymentMethod;
    private final String authorizationDeclineCode;
    private final String captureDeclineCode;
    private final String refundDeclineCode;

    PaymentMethodBehaviour(String paymentMethod, String authorizationDeclineCode,
                           String captureDeclineCode, String refundDeclineCode) {
        this.paymentMethod = paymentMethod;
        this.authorizationDeclineCode = authorizationDeclineCode;
        this.captureDeclineCode = captureDeclineCode;
        this.refundDeclineCode = refundDeclineCode;
    }

    public static Optional<PaymentMethodBehaviour> of(String paymentMethod) {
        return Arrays.stream(values()).filter(b -> b.paymentMethod.equals(paymentMethod)).findFirst();
    }

    public String paymentMethod() {
        return paymentMethod;
    }

    public Optional<String> authorizationDeclineCode() {
        return Optional.ofNullable(authorizationDeclineCode);
    }

    public Optional<String> captureDeclineCode() {
        return Optional.ofNullable(captureDeclineCode);
    }

    public Optional<String> refundDeclineCode() {
        return Optional.ofNullable(refundDeclineCode);
    }
}
