package com.shylesh.payment_service.repository;

import com.shylesh.payment_service.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {
    Optional<Payment> findByIdempotencyKey(String idempotencyKey);
}