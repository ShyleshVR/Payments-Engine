package com.shylesh.reconciliation_service.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ReconciliationDiscrepancyRepository extends JpaRepository<ReconciliationDiscrepancy, Long> {

    List<ReconciliationDiscrepancy> findByRunIdOrderByIdAsc(UUID runId);

    /** Discrepancies are saved while a run works; a run that doesn't complete keeps none. */
    @Modifying
    @Query("DELETE FROM ReconciliationDiscrepancy d WHERE d.runId = :runId")
    int deleteByRunId(@Param("runId") UUID runId);
}
