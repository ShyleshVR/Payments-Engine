package com.shylesh.payment_service.saga;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Reserves due sagas (SagaReservations: a disjoint batch per replica) and hands each to the
 * worker pool without waiting, so the next poll runs on schedule even while processor calls are
 * slow. Runs on every replica. While batches come back full, it reserves again at once rather
 * than waiting for the next poll.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SagaWorker {

    private final SagaReservations reservations;
    private final SagaOrchestrator orchestrator;
    private final ExecutorService sagaStepExecutor;
    private final SagaProperties properties;

    private static final int MIN_BATCH_TO_CONTINUE = 10;

    private final AtomicInteger inFlight = new AtomicInteger();

    @Scheduled(fixedDelayString = "${payflow.saga.poll-interval:500ms}")
    public void runDueSagas() {
        while (true) {
            int capacity = properties.maxInFlight() - inFlight.get();
            if (capacity <= 0) {
                return;
            }
            SagaReservations.Reservation reservation = reservations.reserve(LocalDateTime.now(), properties.lease(), capacity);
            for (UUID sagaId : reservation.sagaIds()) {
                inFlight.incrementAndGet();
                try {
                    sagaStepExecutor.execute(() -> run(sagaId, reservation.until()));
                } catch (RuntimeException e) {
                    // the reservation expires and another poll takes the saga
                    inFlight.decrementAndGet();
                    log.error("Could not schedule saga {}: {}", sagaId, e.getMessage());
                }
            }
            // more may be due: go again at once, unless that would mean many tiny reservations
            if (reservation.sagaIds().size() < capacity || capacity < MIN_BATCH_TO_CONTINUE) {
                return;
            }
        }
    }

    int inFlightCount() {
        return inFlight.get();
    }

    private void run(UUID sagaId, LocalDateTime reservedUntil) {
        try {
            orchestrator.runReservedStep(sagaId, reservedUntil);
        } catch (Exception e) {
            // e.g. the database is down: the reservation expires and a later poll picks it up again
            log.error("Saga step failed unexpectedly. sagaId={}: {}", sagaId, e.getMessage(), e);
        } finally {
            inFlight.decrementAndGet();
        }
    }
}
