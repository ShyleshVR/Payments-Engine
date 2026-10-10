package com.shylesh.payout_service.saga;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The payout saga of one payout: where it is, when the worker acts next, and what it is waiting
 * for. Changed only under a row lock (worker claim, ledger reply, operator), with @Version as a
 * second guard.
 */
@Entity
@Table(name = "payout_saga")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PayoutSaga {

    public static final int MAX_ERROR_LENGTH = 1000;

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "payout_id", nullable = false, updatable = false)
    private UUID payoutId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private SagaState state;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(nullable = false)
    private int attempt;

    @Column(name = "next_attempt_at")
    private LocalDateTime nextAttemptAt;

    @Column(name = "step_started_at", nullable = false)
    private LocalDateTime stepStartedAt;

    @Column(name = "pending_command_id")
    private UUID pendingCommandId;

    /** Why the bank declined, failed or returned the transfer (carried to the payout). */
    @Column(name = "failure_code", length = 64)
    private String failureCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "stuck_state", length = 40)
    private SagaState stuckState;

    @Column(name = "last_error", length = MAX_ERROR_LENGTH)
    private String lastError;

    @Column(name = "trace_parent", length = 100, updatable = false)
    private String traceParent;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    public static PayoutSaga start(UUID payoutId, String traceParent, LocalDateTime now) {
        return PayoutSaga.builder()
                .id(UUID.randomUUID())
                .payoutId(payoutId)
                .state(SagaState.HOLDING)
                .attempt(0)
                .stepStartedAt(now)
                .traceParent(traceParent)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    public boolean isActive() {
        return finishedAt == null;
    }

    /** Enters a new step: attempts and the step clock start over. */
    public void moveTo(SagaState next, LocalDateTime now) {
        this.state = next;
        this.attempt = 0;
        this.stepStartedAt = now;
        this.pendingCommandId = null;
        this.lastError = null;
        this.updatedAt = now;
    }

    /** The worker acts at this time (null: only a ledger reply or an operator moves the saga on). */
    public void scheduleAt(LocalDateTime at, LocalDateTime now) {
        this.nextAttemptAt = at;
        this.updatedAt = now;
    }

    /** A worker took the current step: count the attempt and lease the saga until leaseUntil. */
    public void beginAttempt(LocalDateTime leaseUntil, LocalDateTime now) {
        this.attempt++;
        this.nextAttemptAt = leaseUntil;
        this.updatedAt = now;
    }

    /** A ledger command was sent (or re-sent); wait for its reply until replyDueAt. */
    public void awaitReply(UUID commandId, LocalDateTime replyDueAt, LocalDateTime now) {
        this.pendingCommandId = commandId;
        this.attempt++;
        this.nextAttemptAt = replyDueAt;
        this.updatedAt = now;
    }

    public void retryLater(LocalDateTime at, String error, LocalDateTime now) {
        this.nextAttemptAt = at;
        this.lastError = truncate(error);
        this.updatedAt = now;
    }

    public void recordFailureCode(String failureCode) {
        this.failureCode = failureCode;
    }

    public void finish(SagaState terminal, LocalDateTime now) {
        this.state = terminal;
        this.nextAttemptAt = null;
        this.pendingCommandId = null;
        this.finishedAt = now;
        this.updatedAt = now;
    }

    /** Stops the saga where it is, for an operator. */
    public void park(String reason, LocalDateTime now) {
        this.stuckState = state;
        this.state = SagaState.REQUIRES_ATTENTION;
        this.nextAttemptAt = null;
        this.lastError = truncate(reason);
        this.updatedAt = now;
    }

    /** Operator retry: the parked step runs again with a fresh clock (same idempotency key). */
    public void resume(LocalDateTime now) {
        SagaState resumed = stuckState;
        this.stuckState = null;
        moveTo(resumed, now);
        this.nextAttemptAt = now;
    }

    private static String truncate(String error) {
        if (error == null || error.length() <= MAX_ERROR_LENGTH) {
            return error;
        }
        return error.substring(0, MAX_ERROR_LENGTH);
    }
}
