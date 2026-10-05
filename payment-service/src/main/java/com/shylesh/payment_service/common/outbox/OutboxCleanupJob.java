package com.shylesh.payment_service.common.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Deletes PUBLISHED outbox rows once they are past retention, so the table holds only the
 * in-flight backlog plus a recent window for audit/replay. PENDING and FAILED rows are never
 * touched.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxCleanupJob {

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxProperties properties;

    @Scheduled(fixedDelayString = "${outbox.cleanup.interval:PT1H}")
    @Transactional
    public void deletePublishedEvents() {
        LocalDateTime cutoff = LocalDateTime.now().minus(properties.cleanup().retention());

        int deleted = outboxEventRepository.deleteByStatusAndPublishedAtBefore(OutboxEventStatus.PUBLISHED, cutoff);

        if (deleted > 0) {
            log.info("Deleted {} published outbox event(s) older than {}", deleted, cutoff);
        }
    }
}
