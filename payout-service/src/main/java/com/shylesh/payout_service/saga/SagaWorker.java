package com.shylesh.payout_service.saga;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 * Polls for due sagas and hands each to the worker pool without waiting, so the next poll runs on
 * schedule even while bank calls are slow. Runs on every replica: each saga is claimed
 * individually (SKIP LOCKED + lease), and one already in flight here is never submitted twice.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SagaWorker {

    private final PayoutSagaRepository sagaRepository;
    private final PayoutOrchestrator orchestrator;
    private final ExecutorService sagaStepExecutor;
    private final SagaProperties properties;

    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    @Scheduled(fixedDelayString = "${payflow.payouts.saga.poll-interval:500ms}")
    public void runDueSagas() {
        int capacity = properties.maxInFlight() - inFlight.size();
        if (capacity <= 0) {
            return;
        }
        List<UUID> due = sagaRepository.findDueIds(LocalDateTime.now(), Limit.of(properties.maxInFlight())).stream()
                .filter(id -> !inFlight.contains(id))
                .limit(capacity)
                .toList();
        for (UUID sagaId : due) {
            inFlight.add(sagaId);
            try {
                sagaStepExecutor.execute(() -> run(sagaId));
            } catch (RuntimeException e) {
                inFlight.remove(sagaId);
                log.error("Could not schedule payout saga {}: {}", sagaId, e.getMessage());
            }
        }
    }

    private void run(UUID sagaId) {
        try {
            orchestrator.runDueStep(sagaId);
        } catch (Exception e) {
            // e.g. the database is down: the saga stays due (or its lease expires) and a later
            // poll picks it up again
            log.error("Payout saga step failed unexpectedly. sagaId={}: {}", sagaId, e.getMessage(), e);
        } finally {
            inFlight.remove(sagaId);
        }
    }
}
