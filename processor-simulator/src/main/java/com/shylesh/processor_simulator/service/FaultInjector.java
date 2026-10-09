package com.shylesh.processor_simulator.service;

import com.shylesh.processor_simulator.config.ProcessorProperties;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Failures a real processor produces that have nothing to do with the request: outages, random
 * errors, latency, and calls that fail a few times before succeeding. A failed call is answered
 * 503 and not processed at all, so retrying it with the same Idempotency-Key is always safe.
 * Outage and attempt counts live in the database, so every replica behaves the same.
 */
@Component
@RequiredArgsConstructor
public class FaultInjector {

    private final ProcessorProperties properties;
    private final JdbcTemplate jdbcTemplate;

    /** @return why the request fails, or empty to process it */
    public Optional<String> failure(String idempotencyKey, String paymentMethod) {
        sleep(properties.chaos().latency());

        if (inOutage()) {
            return Optional.of("Processor outage (simulated)");
        }
        if (properties.chaos().errorRate() > 0 && ThreadLocalRandom.current().nextDouble() < properties.chaos().errorRate()) {
            return Optional.of("Random processor error (simulated)");
        }
        if (PaymentMethodBehaviour.FLAKY.paymentMethod().equals(paymentMethod)
                && attempt(idempotencyKey) <= properties.flakyFailures()) {
            return Optional.of("Transient processor error (pm_card_flaky)");
        }
        return Optional.empty();
    }

    public void startOutage(Duration duration) {
        jdbcTemplate.update("UPDATE simulator_state SET outage_until = ? WHERE id = 1",
                Timestamp.valueOf(LocalDateTime.now().plus(duration)));
    }

    public void endOutage() {
        jdbcTemplate.update("UPDATE simulator_state SET outage_until = NULL WHERE id = 1");
    }

    private boolean inOutage() {
        Timestamp until = jdbcTemplate.queryForObject("SELECT outage_until FROM simulator_state WHERE id = 1", Timestamp.class);
        return until != null && until.toLocalDateTime().isAfter(LocalDateTime.now());
    }

    private int attempt(String idempotencyKey) {
        Integer attempts = jdbcTemplate.queryForObject("""
                INSERT INTO operation_attempts (idempotency_key, attempts) VALUES (?, 1)
                ON CONFLICT (idempotency_key) DO UPDATE SET attempts = operation_attempts.attempts + 1
                RETURNING attempts
                """, Integer.class, idempotencyKey);
        return attempts == null ? 1 : attempts;
    }

    static void sleep(Duration duration) {
        if (duration.isZero() || duration.isNegative()) {
            return;
        }
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
