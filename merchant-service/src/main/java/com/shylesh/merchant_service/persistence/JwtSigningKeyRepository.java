package com.shylesh.merchant_service.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface JwtSigningKeyRepository extends JpaRepository<JwtSigningKey, String> {

    Optional<JwtSigningKey> findByStatus(SigningKeyStatus status);
}
