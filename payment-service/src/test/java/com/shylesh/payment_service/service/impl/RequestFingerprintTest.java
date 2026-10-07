package com.shylesh.payment_service.service.impl;

import com.shylesh.payment_service.dto.CreatePaymentRequest;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RequestFingerprintTest {

    private final RequestFingerprint fingerprint = new RequestFingerprint();
    private final UUID merchantId = UUID.randomUUID();

    private CreatePaymentRequest request(String amount, String description) {
        return CreatePaymentRequest.builder()
                .amount(new BigDecimal(amount))
                .currency("USD")
                .description(description)
                .build();
    }

    @Test
    void sameAmountWithDifferentScaleIsTheSameRequest() {
        assertThat(fingerprint.of(merchantId, request("10", null))).isEqualTo(fingerprint.of(merchantId, request("10.00", null)));
    }

    @Test
    void differentAmountIsADifferentRequest() {
        assertThat(fingerprint.of(merchantId, request("10.00", null))).isNotEqualTo(fingerprint.of(merchantId, request("10.01", null)));
    }

    @Test
    void missingAndEmptyDescriptionAreDistinguished() {
        assertThat(fingerprint.of(merchantId, request("10", null))).isNotEqualTo(fingerprint.of(merchantId, request("10", "")));
    }

    @Test
    void sameBodyFromAnotherMerchantIsADifferentRequest() {
        assertThat(fingerprint.of(merchantId, request("10", null)))
                .isNotEqualTo(fingerprint.of(UUID.randomUUID(), request("10", null)));
    }
}
