package com.shylesh.payout_service.batch;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** One day's payout batch: when it ran, what it created, and whether it finished. */
@Entity
@Table(name = "payout_batch")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PayoutBatch {

    public enum Status { RUNNING, COMPLETED, FAILED }

    @Id
    @Column(name = "batch_date", nullable = false, updatable = false)
    private LocalDate batchDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(nullable = false)
    private LocalDateTime cutoff;

    @Column(name = "payouts_created")
    private Integer payoutsCreated;

    @Column(name = "skipped_no_destination")
    private Integer skippedNoDestination;

    @Column(length = 1000)
    private String error;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    public void restart(LocalDateTime cutoff, LocalDateTime now) {
        this.status = Status.RUNNING;
        this.cutoff = cutoff;
        this.error = null;
        this.startedAt = now;
        this.finishedAt = null;
    }

    public void complete(int created, int skipped, LocalDateTime now) {
        this.status = Status.COMPLETED;
        this.payoutsCreated = created;
        this.skippedNoDestination = skipped;
        this.finishedAt = now;
    }

    public void fail(String error, LocalDateTime now) {
        this.status = Status.FAILED;
        this.error = error == null ? null : error.substring(0, Math.min(error.length(), 1000));
        this.finishedAt = now;
    }
}
