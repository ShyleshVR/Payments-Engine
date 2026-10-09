package com.shylesh.payment_service.saga;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PaymentSagaStepRepository extends JpaRepository<PaymentSagaStep, Long> {

    List<PaymentSagaStep> findBySagaIdOrderByIdAsc(UUID sagaId);
}
