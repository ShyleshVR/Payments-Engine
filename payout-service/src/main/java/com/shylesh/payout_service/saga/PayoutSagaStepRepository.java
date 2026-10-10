package com.shylesh.payout_service.saga;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PayoutSagaStepRepository extends JpaRepository<PayoutSagaStep, Long> {

    List<PayoutSagaStep> findBySagaIdOrderByIdAsc(UUID sagaId);
}
