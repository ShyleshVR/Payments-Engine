package com.shylesh.merchant_service.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MerchantCredentialRepository extends JpaRepository<MerchantCredential, UUID> {

    Optional<MerchantCredential> findByClientId(String clientId);

    Optional<MerchantCredential> findByClientIdAndMerchantId(String clientId, UUID merchantId);

    List<MerchantCredential> findByMerchantIdOrderByCreatedAtAsc(UUID merchantId);

    long countByMerchantIdAndStatus(UUID merchantId, CredentialStatus status);
}
