package com.shylesh.payout_service.batch;

import com.shylesh.payout_service.client.LedgerClient;
import com.shylesh.payout_service.config.PayoutProperties;
import com.shylesh.payout_service.payout.PayoutDestination;
import com.shylesh.payout_service.payout.PayoutDestinationRepository;
import com.shylesh.payout_service.payout.PayoutService;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * One day's payout batch: every merchant with a payout destination and at least the minimum
 * payable gets one payout per currency of their whole payable balance. Safe to run again: a
 * merchant already paid that day is skipped (unique per merchant, currency and day), so a
 * re-run only picks up what the previous one missed. Runs under the batch lock (DailyPayoutJob).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PayoutBatchService {

    private final PayoutBatchRepository batchRepository;
    private final PayoutDestinationRepository destinationRepository;
    private final PayoutService payoutService;
    private final LedgerClient ledgerClient;
    private final PayoutProperties properties;
    private final BatchMetrics metrics;
    private final MeterRegistry meterRegistry;

    public PayoutBatch run(LocalDate batchDate) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime cutoff = payoutService.cutoff(now);
        PayoutBatch batch = batchRepository.findById(batchDate)
                .orElseGet(() -> PayoutBatch.builder().batchDate(batchDate).build());
        batch.restart(cutoff, now);
        batch = batchRepository.save(batch);

        int created = 0;
        int skipped = 0;
        try {
            List<LedgerClient.PayableBalance> balances = ledgerClient.payableBalances(cutoff, properties.minimumAmount());
            for (LedgerClient.PayableBalance balance : balances) {
                Optional<PayoutDestination> destination = destinationRepository.findById(balance.merchantId());
                if (destination.isEmpty()) {
                    skipped++;
                    continue;
                }
                if (payoutService.createBatchPayout(balance.merchantId(), balance.currency(), balance.payable(),
                        destination.get().getBankAccount(), batchDate, cutoff).isPresent()) {
                    created++;
                }
            }
            batch.complete(created, skipped, LocalDateTime.now());
            log.info("Payout batch {} done: {} payout(s) created, {} merchant(s) skipped without a destination (cutoff {})",
                    batchDate, created, skipped, cutoff);
            count("COMPLETED");
        } catch (RuntimeException e) {
            batch.fail(e.getClass().getSimpleName() + ": " + e.getMessage(), LocalDateTime.now());
            log.error("Payout batch {} failed after {} payout(s): {}", batchDate, created, e.getMessage());
            count("FAILED");
        }
        PayoutBatch saved = batchRepository.save(batch);
        metrics.refresh();
        return saved;
    }

    private void count(String status) {
        Counter.builder("payout.batches").tag("status", status).register(meterRegistry).increment();
    }
}
