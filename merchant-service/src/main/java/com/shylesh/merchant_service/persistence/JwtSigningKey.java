package com.shylesh.merchant_service.persistence;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "jwt_signing_keys")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JwtSigningKey {

    @Id
    @Column(nullable = false, updatable = false, length = 64)
    private String kid;

    @Column(name = "public_key", nullable = false, updatable = false, columnDefinition = "TEXT")
    private String publicKey;

    @Column(name = "private_key", nullable = false, updatable = false, columnDefinition = "TEXT")
    private String privateKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SigningKeyStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
