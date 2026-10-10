package com.shylesh.payment_service.saga;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Saga reservations against a real Postgres: concurrent workers get disjoint batches (what lets
 * replicas add throughput instead of racing for the same rows), only due sagas are handed out,
 * and a step can claim a saga only with the reservation it was given.
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(SagaReservations.class)
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SagaReservationsTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @Autowired
    private SagaReservations reservations;

    @Autowired
    private PaymentSagaRepository sagaRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    private static final Duration LEASE = Duration.ofSeconds(30);

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM payment_saga_step");
        jdbcTemplate.update("DELETE FROM payment_saga");
        jdbcTemplate.update("DELETE FROM payment");
    }

    /** A payment with an active saga, due at the given time (null: not due, waiting for a reply). */
    private UUID saga(LocalDateTime dueAt) {
        UUID paymentId = UUID.randomUUID();
        UUID sagaId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("INSERT INTO payment (id, amount, currency, merchant_id, status, created_at, updated_at) "
                + "VALUES (?, 10.00, 'USD', ?, 'PROCESSING', ?, ?)", paymentId, UUID.randomUUID(), Timestamp.valueOf(now), Timestamp.valueOf(now));
        jdbcTemplate.update("INSERT INTO payment_saga (id, payment_id, type, state, version, attempt, next_attempt_at, step_started_at, "
                        + "created_at, updated_at) VALUES (?, ?, 'PAYMENT', 'AUTHORIZING', 0, 0, ?, ?, ?, ?)",
                sagaId, paymentId, dueAt == null ? null : Timestamp.valueOf(dueAt), Timestamp.valueOf(now), Timestamp.valueOf(now),
                Timestamp.valueOf(now));
        return sagaId;
    }

    @Test
    void concurrentWorkersGetDisjointBatchesOfDueSagasOnly() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        Set<UUID> due = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            due.add(saga(now.minusSeconds(1)));
        }
        UUID later = saga(now.plusMinutes(5));
        UUID waiting = saga(null);

        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<UUID> handedOut = new ArrayList<>();
        try {
            List<Callable<SagaReservations.Reservation>> workers = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                workers.add(() -> reservations.reserve(LocalDateTime.now(), LEASE, 80));
            }
            for (Future<SagaReservations.Reservation> f : pool.invokeAll(workers)) {
                handedOut.addAll(f.get().sagaIds());
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(handedOut).doesNotHaveDuplicates();
        assertThat(new HashSet<>(handedOut)).isEqualTo(due);
        assertThat(handedOut).doesNotContain(later, waiting);
        // reserved: nothing is due any more until the reservations expire
        assertThat(reservations.reserve(LocalDateTime.now(), LEASE, 100).sagaIds()).isEmpty();
        assertThat(reservations.reserve(LocalDateTime.now().plus(LEASE).plusSeconds(1), LEASE, 500).sagaIds()).hasSize(200);
    }

    @Test
    void aSagaCanOnlyBeClaimedWithItsCurrentReservation() {
        UUID sagaId = saga(LocalDateTime.now().minusSeconds(1));
        SagaReservations.Reservation reservation = reservations.reserve(LocalDateTime.now(), LEASE, 10);
        assertThat(reservation.sagaIds()).containsExactly(sagaId);
        assertThat(claim(sagaId, reservation.until().minusNanos(1000))).isEmpty();
        assertThat(claim(sagaId, reservation.until())).isPresent();

        // something else moved the saga on (a ledger reply): the old reservation no longer claims it
        jdbcTemplate.update("UPDATE payment_saga SET next_attempt_at = NULL WHERE id = ?", sagaId);
        assertThat(claim(sagaId, reservation.until())).isEmpty();
    }

    /**
     * Under load, another replica's reservation query can hold a saga's row lock for a moment
     * just as the worker claims it. The claim must wait for it: skipping the row would leave the
     * saga reserved, unrun, until its lease expired (the 30 s tail the soak test found).
     */
    @Test
    void aClaimWaitsForAMomentaryLockInsteadOfSkippingTheSaga() throws Exception {
        UUID sagaId = saga(LocalDateTime.now().minusSeconds(1));
        SagaReservations.Reservation reservation = reservations.reserve(LocalDateTime.now(), LEASE, 10);
        assertThat(reservation.sagaIds()).containsExactly(sagaId);

        java.util.concurrent.CountDownLatch locked = new java.util.concurrent.CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> holder = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                jdbcTemplate.queryForList("SELECT id FROM payment_saga WHERE id = ? FOR UPDATE", sagaId);
                locked.countDown();
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            locked.await();

            assertThat(claim(sagaId, reservation.until())).isPresent();
            holder.get();
        } finally {
            pool.shutdownNow();
        }
    }

    private java.util.Optional<PaymentSaga> claim(UUID sagaId, LocalDateTime reservedUntil) {
        java.util.Optional<PaymentSaga> locked = new TransactionTemplate(transactionManager)
                .execute(status -> sagaRepository.lockReserved(sagaId, reservedUntil));
        return locked == null ? java.util.Optional.empty() : locked;
    }
}
