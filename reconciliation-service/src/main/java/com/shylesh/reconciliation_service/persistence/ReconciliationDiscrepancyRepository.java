package com.shylesh.reconciliation_service.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ReconciliationDiscrepancyRepository extends JpaRepository<ReconciliationDiscrepancy, Long> {

    List<ReconciliationDiscrepancy> findByRunIdOrderByIdAsc(UUID runId);
}
