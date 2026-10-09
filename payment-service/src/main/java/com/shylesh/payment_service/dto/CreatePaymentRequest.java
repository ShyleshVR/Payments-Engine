package com.shylesh.payment_service.dto;

import com.shylesh.payment_service.entity.CaptureMethod;
import jakarta.validation.constraints.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
/** The merchant is not part of the request: it is always taken from the caller's token. */
public class CreatePaymentRequest {

    @NotNull
    @DecimalMin(value = "0.01")
    private BigDecimal amount;

    @NotBlank
    @Size(min = 3, max = 3)
    private String currency;

    private UUID customerId;

    @Size(max = 255)
    private String description;

    /** Card token, e.g. pm_card_visa (see the processor's test payment methods). */
    @NotBlank
    @Size(max = 64)
    @Pattern(regexp = "pm_[a-z0-9_]+", message = "must be a payment method token such as pm_card_visa")
    private String paymentMethod;

    /** AUTOMATIC (default): captured right away. MANUAL: authorized only, captured by POST .../capture. */
    private CaptureMethod captureMethod;

    public CaptureMethod captureMethodOrDefault() {
        return captureMethod == null ? CaptureMethod.AUTOMATIC : captureMethod;
    }
}