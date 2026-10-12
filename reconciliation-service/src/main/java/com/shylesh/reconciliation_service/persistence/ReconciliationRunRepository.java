package com.shylesh.reconciliation_service.persistence;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReconciliationRunRepository extends JpaRepository<ReconciliationRun, UUID> {

    boolean existsByBusinessDateAndStatus(LocalDate businessDate, RunStatus status);

    List<ReconciliationRun> findByBusinessDateOrderByStartedAtDesc(LocalDate businessDate);

    List<ReconciliationRun> findAllByOrderByStartedAtDesc(Limit limit);

    Optional<ReconciliationRun> findFirstByStatusOrderByFinishedAtDesc(RunStatus status);

    List<ReconciliationRun> findByStatusAndBusinessDateGreaterThanEqual(RunStatus status, LocalDate since);

    List<ReconciliationRun> findByBusinessDateAndStatus(LocalDate businessDate, RunStatus status);

    /** Runs that ended since :since, whatever their day. */
    @Query("""
            SELECT r.id FROM ReconciliationRun r
            WHERE r.status <> com.shylesh.reconciliation_service.persistence.RunStatus.RUNNING AND r.finishedAt >= :since
            """)
    List<UUID> findIdsOfRunsEndedSince(@Param("since") LocalDateTime since);

    /**
     * The run says it is alive. Returns 0 when it is no longer RUNNING: another attempt found it
     * silent and took its day over, so this one must stop without writing a result.
     */
    @Modifying
    @Query("""
            UPDATE ReconciliationRun r SET r.heartbeatAt = :now
            WHERE r.id = :id AND r.status = com.shylesh.reconciliation_service.persistence.RunStatus.RUNNING
            """)
    int heartbeat(@Param("id") UUID id, @Param("now") LocalDateTime now);

    /** Ends a RUNNING run that has been silent since before :silentSince; 0 if it spoke or ended meanwhile. */
    @Modifying
    @Query("""
            UPDATE ReconciliationRun r
            SET r.status = com.shylesh.reconciliation_service.persistence.RunStatus.FAILED, r.error = :error, r.finishedAt = :now
            WHERE r.id = :id AND r.status = com.shylesh.reconciliation_service.persistence.RunStatus.RUNNING
              AND coalesce(r.heartbeatAt, r.startedAt) < :silentSince
            """)
    int abandon(@Param("id") UUID id, @Param("error") String error, @Param("now") LocalDateTime now,
                @Param("silentSince") LocalDateTime silentSince);
}
