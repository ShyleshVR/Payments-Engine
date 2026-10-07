package com.shylesh.merchant_service.persistence;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "merchant_credentials")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MerchantCredential {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "merchant_id", nullable = false, updatable = false)
    private UUID merchantId;

    @Column(name = "client_id", nullable = false, updatable = false, length = 64)
    private String clientId;

    /** PasswordEncoder output with its {id} prefix, e.g. {bcrypt}$2a$10$... */
    @Column(name = "client_secret_hash", nullable = false, updatable = false)
    private String clientSecretHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CredentialStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    public boolean isActive() {
        return status == CredentialStatus.ACTIVE;
    }

    public void revoke(LocalDateTime now) {
        if (isActive()) {
            this.status = CredentialStatus.REVOKED;
            this.revokedAt = now;
        }
    }
}
