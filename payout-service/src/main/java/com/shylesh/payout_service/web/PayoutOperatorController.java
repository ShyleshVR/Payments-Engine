package com.shylesh.payout_service.web;

import com.shylesh.payout_service.batch.DailyPayoutJob;
import com.shylesh.payout_service.payout.PayoutStateException;
import com.shylesh.payout_service.saga.PayoutOrchestrator;
import com.shylesh.payout_service.web.PayoutViews.AuditView;
import com.shylesh.payout_service.web.PayoutViews.BatchView;
import com.shylesh.payout_service.web.PayoutViews.LookupRequest;
import com.shylesh.payout_service.web.PayoutViews.SagaView;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** Operators: run the batch, inspect and resume payout sagas. Reconciliation: look payouts up. */
@RestController
@RequestMapping("/api/v1/payouts")
@RequiredArgsConstructor
public class PayoutOperatorController {

    private final DailyPayoutJob job;
    private final PayoutOrchestrator orchestrator;
    private final PayoutQueries queries;

    /** Runs today's batch now (it only creates payouts that don't exist yet). 409 while another run holds the lock. */
    @PostMapping("/batches")
    @PreAuthorize("hasAuthority(T(com.shylesh.payout_service.security.Scopes).PAYOUTS_OPERATE)")
    public BatchView runBatch() {
        return job.runNow().map(BatchView::of)
                .orElseThrow(() -> new PayoutStateException("A payout batch is running right now"));
    }

    @GetMapping("/batches")
    @PreAuthorize("hasAuthority(T(com.shylesh.payout_service.security.Scopes).PAYOUTS_OPERATE)")
    public List<BatchView> batches(@RequestParam(defaultValue = "20") int limit) {
        return queries.batches(Math.max(1, Math.min(limit, 100)));
    }

    @GetMapping("/{payoutId}/saga")
    @PreAuthorize("hasAuthority(T(com.shylesh.payout_service.security.Scopes).PAYOUTS_OPERATE)")
    public SagaView saga(@PathVariable String payoutId) {
        return queries.saga(PayoutQueries.parseId(payoutId));
    }

    /** Resumes a parked saga at the step it stopped at (same idempotency key for the transfer). */
    @PostMapping("/{payoutId}/saga/retry")
    @PreAuthorize("hasAuthority(T(com.shylesh.payout_service.security.Scopes).PAYOUTS_OPERATE)")
    public SagaView retry(@PathVariable String payoutId) {
        UUID id = PayoutQueries.parseId(payoutId);
        orchestrator.retry(id);
        return queries.saga(id);
    }

    /** Payouts by id, with whether their money movements may still be incomplete (reconciliation). */
    @PostMapping("/lookup")
    @PreAuthorize("hasAnyAuthority(T(com.shylesh.payout_service.security.Scopes).PAYOUTS_AUDIT, T(com.shylesh.payout_service.security.Scopes).PAYOUTS_OPERATE)")
    public List<AuditView> lookup(@Valid @RequestBody LookupRequest request) {
        return queries.lookup(request.payoutIds().stream().map(PayoutQueries::parseId).toList());
    }
}
