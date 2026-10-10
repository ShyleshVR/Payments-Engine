package com.shylesh.payment_service.common.outbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The relay's claim query against a real Postgres: only each aggregate's earliest unpublished
 * event is claimable, and only when due, so per-payment order on Kafka matches commit order.
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OutboxClaimQueryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @Autowired
    private OutboxEventRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM outbox_event");
    }

    /** Inserts an event; seq follows insertion order. */
    private UUID event(UUID aggregateId, String status, LocalDateTime nextAttemptAt) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO outbox_event (id, aggregate_id, aggregate_type, event_type, payload, status, created_at, next_attempt_at) "
                        + "VALUES (?, ?, 'PAYMENT', 'TEST', '{}', ?, ?, ?)",
                id, aggregateId, status, Timestamp.valueOf(LocalDateTime.now()),
                nextAttemptAt == null ? null : Timestamp.valueOf(nextAttemptAt));
        return id;
    }

    private List<UUID> claim(int limit) {
        return new TransactionTemplate(transactionManager).execute(status ->
                repository.claimPublishable(LocalDateTime.now(), limit).stream().map(OutboxEvent::getId).toList());
    }

    @Test
    void onlyTheEarliestUnpublishedDueEventOfEachAggregateIsClaimed() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID(), d = UUID.randomUUID();
        UUID a1 = event(a, "PENDING", null);
        event(a, "PENDING", null);                                 // waits behind a1
        event(b, "PUBLISHED", null);
        UUID b2 = event(b, "PENDING", null);                       // b1 is out: b2 is next
        event(c, "FAILED", null);
        event(c, "PENDING", null);                                 // parked behind a FAILED event
        event(d, "PENDING", LocalDateTime.now().plusMinutes(1));   // backing off: not due yet
        event(d, "PENDING", null);                                 // and nothing overtakes it

        assertThat(claim(100)).containsExactly(a1, b2);
        assertThat(claim(1)).containsExactly(a1);
    }
}
