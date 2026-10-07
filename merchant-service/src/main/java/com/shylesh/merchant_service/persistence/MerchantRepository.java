package com.shylesh.merchant_service.persistence;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface MerchantRepository extends JpaRepository<Merchant, UUID> {

    boolean existsByEmailIgnoreCase(String email);

    /**
     * Row lock on the merchant, held for the rest of the transaction. Serialises credential
     * issuance for one merchant so two concurrent requests can't both pass the active-credential
     * limit check.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM Merchant m WHERE m.id = :id")
    Optional<Merchant> findByIdForUpdate(@Param("id") UUID id);
}
