package com.shylesh.merchant_service.dto;

import com.shylesh.merchant_service.persistence.Merchant;
import com.shylesh.merchant_service.persistence.MerchantStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record MerchantResponse(
        UUID merchantId,
        String name,
        String email,
        MerchantStatus status,
        LocalDateTime createdAt,
        List<CredentialResponse> credentials
) {

    public static MerchantResponse of(Merchant merchant, List<CredentialResponse> credentials) {
        return new MerchantResponse(merchant.getId(), merchant.getName(), merchant.getEmail(), merchant.getStatus(),
                merchant.getCreatedAt(), credentials);
    }
}
