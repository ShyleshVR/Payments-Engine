package com.shylesh.reconciliation_service.persistence;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReconciliationRunRepository extends JpaRepository<ReconciliationRun, UUID> {

    boolean existsByBusinessDateAndStatus(LocalDate businessDate, RunStatus status);

    List<ReconciliationRun> findByBusinessDateOrderByStartedAtDesc(LocalDate businessDate);

    List<ReconciliationRun> findAllByOrderByStartedAtDesc(Limit limit);

    Optional<ReconciliationRun> findFirstByStatusOrderByFinishedAtDesc(RunStatus status);

    List<ReconciliationRun> findByStatusAndBusinessDateGreaterThanEqual(RunStatus status, LocalDate since);
}
