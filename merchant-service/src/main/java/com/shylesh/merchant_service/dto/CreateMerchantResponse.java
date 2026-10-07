package com.shylesh.merchant_service.dto;

import com.shylesh.merchant_service.persistence.MerchantStatus;

import java.time.LocalDateTime;
import java.util.UUID;

/** Returned once on onboarding, with the merchant's first credential (secret included). */
public record CreateMerchantResponse(
        UUID merchantId,
        String name,
        String email,
        MerchantStatus status,
        LocalDateTime createdAt,
        IssuedCredentialResponse credential
) {
}
