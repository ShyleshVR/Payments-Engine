package com.shylesh.merchant_service.dto;

import com.shylesh.merchant_service.persistence.CredentialStatus;
import com.shylesh.merchant_service.persistence.MerchantCredential;

import java.time.LocalDateTime;

public record CredentialResponse(String clientId, CredentialStatus status, LocalDateTime createdAt, LocalDateTime revokedAt) {

    public static CredentialResponse of(MerchantCredential credential) {
        return new CredentialResponse(credential.getClientId(), credential.getStatus(), credential.getCreatedAt(), credential.getRevokedAt());
    }
}
