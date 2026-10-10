package com.shylesh.reconciliation_service.persistence;

import com.shylesh.reconciliation_service.domain.Records;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/** One reconciliation of one business day (UTC). */
@Entity
@Table(name = "reconciliation_run")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReconciliationRun {

    public static final int MAX_ERROR_LENGTH = 1000;

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "business_date", nullable = false, updatable = false)
    private LocalDate businessDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, updatable = false, length = 20)
    private RunTrigger trigger;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RunStatus status;

    @Column(name = "started_at", nullable = false, updatable = false)
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "payments_checked")
    private Integer paymentsChecked;

    private Integer matched;

    private Integer pending;

    @Column(name = "payouts_checked")
    private Integer payoutsChecked;

    @Column(name = "payouts_matched")
    private Integer payoutsMatched;

    @Column(name = "payouts_pending")
    private Integer payoutsPending;

    @Column(name = "discrepancy_count")
    private Integer discrepancyCount;

    @Column(length = MAX_ERROR_LENGTH)
    private String error;

    public static ReconciliationRun start(LocalDate businessDate, RunTrigger trigger, LocalDateTime now) {
        return ReconciliationRun.builder()
                .id(UUID.randomUUID())
                .businessDate(businessDate)
                .trigger(trigger)
                .status(RunStatus.RUNNING)
                .startedAt(now)
                .build();
    }

    public void complete(Records.Result result, LocalDateTime now) {
        this.status = RunStatus.COMPLETED;
        this.paymentsChecked = result.checked();
        this.matched = result.matched();
        this.pending = result.pending();
        this.payoutsChecked = result.payoutsChecked();
        this.payoutsMatched = result.payoutsMatched();
        this.payoutsPending = result.payoutsPending();
        this.discrepancyCount = result.discrepancies().size();
        this.finishedAt = now;
    }

    public void fail(String error, LocalDateTime now) {
        this.status = RunStatus.FAILED;
        this.error = error == null || error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
        this.finishedAt = now;
    }
}
