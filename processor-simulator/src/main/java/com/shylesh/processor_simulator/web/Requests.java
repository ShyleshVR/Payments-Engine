package com.shylesh.processor_simulator.web;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** Request bodies of the processor API. */
public final class Requests {

    private Requests() {
    }

    /** @param reference the caller's own id for the payment, echoed for reconciliation */
    public record Authorize(
            @NotBlank @Size(max = 64) String paymentMethod,
            @NotNull @DecimalMin("0.01") @Digits(integer = 15, fraction = 4) BigDecimal amount,
            @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
            @Size(max = 100) String reference
    ) {
    }

    public record Refund(
            @NotBlank @Size(max = 40) String authorizationId,
            @NotNull @DecimalMin("0.01") @Digits(integer = 15, fraction = 4) BigDecimal amount
    ) {
    }

    /**
     * A payout to a merchant's bank account.
     *
     * @param reference the caller's own id for the payout, echoed for reconciliation
     */
    public record Transfer(
            @NotBlank @Size(max = 64) String bankAccount,
            @NotNull @DecimalMin("0.01") @Digits(integer = 15, fraction = 4) BigDecimal amount,
            @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
            @Size(max = 100) String reference
    ) {
    }

    /** @param originalIdempotencyKey the Idempotency-Key of the authorization request to cancel */
    public record Reversal(
            @NotBlank @Size(max = 100) String originalIdempotencyKey
    ) {
    }
}
