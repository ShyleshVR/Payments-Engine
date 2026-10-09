package com.shylesh.payment_service.saga;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentSagaRepository extends JpaRepository<PaymentSaga, UUID> {

    /** Candidates for the worker; each is then claimed individually with lockIfDue. */
    @Query("""
            SELECT s.id FROM PaymentSaga s
            WHERE s.finishedAt IS NULL AND s.nextAttemptAt <= :now
            ORDER BY s.nextAttemptAt
            """)
    List<UUID> findDueIds(@Param("now") LocalDateTime now, Limit limit);

    /**
     * Locks the saga if it is still due. SKIP LOCKED: a saga another worker (or a reply, or the
     * merchant) is changing right now is skipped, not waited for.
     */
    @Query(value = """
            SELECT * FROM payment_saga
            WHERE id = :id
              AND finished_at IS NULL
              AND next_attempt_at <= :now
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<PaymentSaga> lockIfDue(@Param("id") UUID id, @Param("now") LocalDateTime now);

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
