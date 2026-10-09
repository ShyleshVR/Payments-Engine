package com.shylesh.reconciliation_service.web;

import com.shylesh.reconciliation_service.persistence.ReconciliationDiscrepancyRepository;
import com.shylesh.reconciliation_service.persistence.ReconciliationRun;
import com.shylesh.reconciliation_service.persistence.ReconciliationRunRepository;
import com.shylesh.reconciliation_service.persistence.RunTrigger;
import com.shylesh.reconciliation_service.service.ReconciliationService;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.Limit;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Operator API: reconciliation runs, their discrepancies, and on-demand runs. */
@RestController
@RequestMapping("/api/v1/reconciliation/runs")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority(T(com.shylesh.reconciliation_service.security.SecurityConfig).RECONCILIATION_ADMIN)")
public class ReconciliationController {

    private final ReconciliationService reconciliationService;
    private final ReconciliationRunRepository runRepository;
    private final ReconciliationDiscrepancyRepository discrepancyRepository;
    private final Clock clock;

    /** Runs of one day, newest first; without a date, the 20 most recent runs. */
    @GetMapping
    public List<RunViews.RunSummary> runs(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        List<ReconciliationRun> runs = date == null
                ? runRepository.findAllByOrderByStartedAtDesc(Limit.of(20))
                : runRepository.findByBusinessDateOrderByStartedAtDesc(date);
        return runs.stream().map(RunViews.RunSummary::of).toList();
    }

    @GetMapping("/{runId}")
    public RunViews.RunDetail run(@PathVariable UUID runId) {
        ReconciliationRun run = runRepository.findById(runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No reconciliation run " + runId));
        return detail(run);
    }

    /**
     * Reconciles a day now (e.g. after fixing a discrepancy). Today can be reconciled too, as far
     * as it has gone; the scheduler only reconciles days that are over.
     */
    @PostMapping
    public ResponseEntity<RunViews.RunDetail> trigger(@Valid @RequestBody RunViews.TriggerRequest request) {
        if (request.date().isAfter(LocalDate.now(clock))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A day in the future can not be reconciled");
        }
        ReconciliationRun run = reconciliationService.run(request.date(), RunTrigger.MANUAL);
        return ResponseEntity.status(HttpStatus.CREATED).body(detail(run));
    }

    private RunViews.RunDetail detail(ReconciliationRun run) {
        return new RunViews.RunDetail(RunViews.RunSummary.of(run),
                discrepancyRepository.findByRunIdOrderByIdAsc(run.getId()).stream().map(RunViews.DiscrepancyView::of).toList());
    }
}
