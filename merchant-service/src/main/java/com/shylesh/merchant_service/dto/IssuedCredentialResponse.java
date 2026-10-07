package com.shylesh.merchant_service.dto;

import java.time.LocalDateTime;

/** The only response that ever carries a client secret: it is not stored and can't be shown again. */
public record IssuedCredentialResponse(String clientId, String clientSecret, LocalDateTime createdAt) {
}
