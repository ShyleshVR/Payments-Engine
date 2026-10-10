package com.shylesh.processor_simulator.persistence;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** A payout to a merchant's bank account. */
@Entity
@Table(name = "transfers")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Transfer {

    @Id
    @Column(nullable = false, updatable = false, length = 40)
    private String id;

    @Column(name = "bank_account", nullable = false, updatable = false, length = 64)
    private String bankAccount;

    @Column(nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    /** The caller's own id for the payout, echoed for reconciliation. */
    @Column(updatable = false, length = 100)
    private String reference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransferStatus status;

    @Column(name = "failure_code", length = 64)
    private String failureCode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @Column(name = "failed_at")
    private LocalDateTime failedAt;

    @Column(name = "returned_at")
    private LocalDateTime returnedAt;

    @Column(name = "next_transition_at")
    private LocalDateTime nextTransitionAt;

    public void pay(LocalDateTime now, LocalDateTime returnAt) {
        this.status = TransferStatus.PAID;
        this.paidAt = now;
        this.updatedAt = now;
        this.nextTransitionAt = returnAt;
    }

    public void fail(String code, LocalDateTime now) {
        this.status = TransferStatus.FAILED;
        this.failureCode = code;
        this.failedAt = now;
        this.updatedAt = now;
        this.nextTransitionAt = null;
    }

    public void sendBack(String code, LocalDateTime now) {
        this.status = TransferStatus.RETURNED;
        this.failureCode = code;
        this.returnedAt = now;
        this.updatedAt = now;
        this.nextTransitionAt = null;
    }
}
