package com.shylesh.payment_service.saga;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Hands out due sagas to workers, a disjoint batch per call: the oldest due sagas nobody else
 * holds (SKIP LOCKED) get their next attempt pushed to the reservation's expiry in the same
 * statement, so no other replica sees them as due. The worker then claims each saga by that exact
 * time (PaymentSagaRepository.lockReserved). A reservation nobody claims simply expires.
 */
@Component
@RequiredArgsConstructor
public class SagaReservations {

    private static final String RESERVE = """
            UPDATE payment_saga SET next_attempt_at = ?
            WHERE id IN (
                SELECT id FROM payment_saga
                WHERE finished_at IS NULL AND next_attempt_at <= ?
                ORDER BY next_attempt_at
                LIMIT ?
                FOR UPDATE SKIP LOCKED)
            RETURNING id
            """;

    private final JdbcTemplate jdbcTemplate;

    public record Reservation(List<UUID> sagaIds, LocalDateTime until) {
    }

    public Reservation reserve(LocalDateTime now, java.time.Duration lease, int limit) {
        // the database keeps microseconds: the claim compares against exactly this value
        LocalDateTime until = now.plus(lease).truncatedTo(ChronoUnit.MICROS);
        List<UUID> ids = jdbcTemplate.queryForList(RESERVE, UUID.class, Timestamp.valueOf(until), Timestamp.valueOf(now), limit);
        return new Reservation(ids, until);
    }
}
