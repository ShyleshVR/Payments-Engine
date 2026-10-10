package com.shylesh.payout_service.saga;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PayoutSagaRepository extends JpaRepository<PayoutSaga, UUID> {

    /** Candidates for the worker; each is then claimed individually with lockIfDue. */
    @Query("""
            SELECT s.id FROM PayoutSaga s
            WHERE s.finishedAt IS NULL AND s.nextAttemptAt <= :now
            ORDER BY s.nextAttemptAt
            """)
    List<UUID> findDueIds(@Param("now") LocalDateTime now, Limit limit);

    /**
     * Locks the saga if it is still due. SKIP LOCKED: a saga another worker (or a reply, or an
     * operator) is changing right now is skipped, not waited for.
     */
    @Query(value = """
            SELECT * FROM payout_saga
            WHERE id = :id
              AND finished_at IS NULL
              AND next_attempt_at <= :now
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<PayoutSaga> lockIfDue(@Param("id") UUID id, @Param("now") LocalDateTime now);

    /** Waits for the lock: ledger replies and operator actions must not be skipped. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM PayoutSaga s WHERE s.id = :id")
    Optional<PayoutSaga> findByIdForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM PayoutSaga s WHERE s.payoutId = :payoutId")
    Optional<PayoutSaga> findByPayoutIdForUpdate(@Param("payoutId") UUID payoutId);

    Optional<PayoutSaga> findByPayoutId(UUID payoutId);

    long countByState(SagaState state);

    /**
     * When the oldest step still in progress started. Steps that wait on the bank by design (in
     * transit, return window) and parked sagas are excluded: they have their own timeouts.
     */
    @Query("""
            SELECT MIN(s.stepStartedAt) FROM PayoutSaga s
            WHERE s.finishedAt IS NULL
              AND s.state NOT IN (com.shylesh.payout_service.saga.SagaState.IN_TRANSIT,
                                  com.shylesh.payout_service.saga.SagaState.RETURN_WINDOW,
                                  com.shylesh.payout_service.saga.SagaState.REQUIRES_ATTENTION)
            """)
    LocalDateTime findOldestStepStartInProgress();

    /**
     * Payouts whose money movements may still be incomplete: an active saga outside the return
     * window (there, everything is booked; only a return could still come).
     */
    @Query("""
            SELECT s.payoutId FROM PayoutSaga s
            WHERE s.finishedAt IS NULL AND s.payoutId IN :payoutIds
              AND s.state <> com.shylesh.payout_service.saga.SagaState.RETURN_WINDOW
            """)
    List<UUID> findPayoutIdsInFlight(@Param("payoutIds") Collection<UUID> payoutIds);
}
