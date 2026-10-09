package com.shylesh.payment_service.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Public payment ids (pay_...), at most 1000 per call. */
public record PaymentLookupRequest(@NotEmpty @Size(max = 1000) List<String> paymentIds) {
}
