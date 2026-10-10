package com.shylesh.payment_service.saga;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentSagaRepository extends JpaRepository<PaymentSaga, UUID> {

    /**
     * Locks the saga if SagaWorker's reservation (next_attempt_at = reservedUntil) is still in
     * place. It waits for a lock someone else holds (another replica's reservation query, a reply
     * being applied) rather than skipping the row: skipping would leave a valid reservation unrun
     * until its lease expired. If they changed the saga, the reservation is gone and nothing is
     * returned. Only one row is locked, and the reservation query never waits, so no deadlock.
     */
    @Query(value = """
            SELECT * FROM payment_saga
            WHERE id = :id
              AND finished_at IS NULL
              AND next_attempt_at = :reservedUntil
            FOR UPDATE
            """, nativeQuery = true)
    Optional<PaymentSaga> lockReserved(@Param("id") UUID id, @Param("reservedUntil") LocalDateTime reservedUntil);

    /** Waits for the lock: replies and merchant actions must not be skipped. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM PaymentSaga s WHERE s.id = :id")
    Optional<PaymentSaga> findByIdForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM PaymentSaga s WHERE s.paymentId = :paymentId AND s.finishedAt IS NULL")
    Optional<PaymentSaga> findActiveByPaymentIdForUpdate(@Param("paymentId") UUID paymentId);

    List<PaymentSaga> findByPaymentIdOrderByCreatedAtAsc(UUID paymentId);

    long countByState(SagaState state);

    /**
     * When the oldest step still in progress started, for one saga type. Sagas waiting for the
     * merchant (MANUAL capture) or parked for an operator are not "in progress" and are excluded.
     */
    @Query("""
            SELECT MIN(s.stepStartedAt) FROM PaymentSaga s
            WHERE s.finishedAt IS NULL AND s.type = :type
              AND s.state NOT IN (com.shylesh.payment_service.saga.SagaState.AWAITING_CAPTURE,
                                  com.shylesh.payment_service.saga.SagaState.REQUIRES_ATTENTION)
            """)
    LocalDateTime findOldestStepStartInProgress(@Param("type") SagaType type);

    @Query("SELECT s.paymentId FROM PaymentSaga s WHERE s.finishedAt IS NULL AND s.paymentId IN :paymentIds")
    List<UUID> findPaymentIdsWithActiveSaga(@Param("paymentIds") java.util.Collection<UUID> paymentIds);
}
