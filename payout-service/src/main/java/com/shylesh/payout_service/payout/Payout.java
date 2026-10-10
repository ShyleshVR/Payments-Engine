package com.shylesh.payout_service.payout;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/** One payout of a merchant's money to their bank account. Changed only by its saga. */
@Entity
@Table(name = "payout")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Payout {

    public static final String PUBLIC_ID_PREFIX = "po_";

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "merchant_id", nullable = false, updatable = false)
    private UUID merchantId;

    @Column(nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(name = "bank_account", nullable = false, updatable = false, length = 64)
    private String bankAccount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PayoutStatus status;

    @Column(name = "failure_code", length = 64)
    private String failureCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, updatable = false, length = 20)
    private PayoutTrigger trigger;

    @Column(name = "batch_date", updatable = false)
    private LocalDate batchDate;

    @Column(name = "idempotency_key", updatable = false, length = 100)
    private String idempotencyKey;

    @Column(name = "request_hash", updatable = false, length = 64)
    private String requestHash;

    @Column(nullable = false, updatable = false)
    private LocalDateTime cutoff;

    @Column(name = "transfer_id", length = 40)
    private String transferId;

    @Version
    @Column(nullable = false)
    private Long version;

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

    public String publicId() {
        return PUBLIC_ID_PREFIX + id;
    }

    public void markInTransit(String transferId, LocalDateTime now) {
        this.status = PayoutStatus.IN_TRANSIT;
        this.transferId = transferId;
        this.updatedAt = now;
    }

    public void markPaid(LocalDateTime now) {
        this.status = PayoutStatus.PAID;
        this.paidAt = now;
        this.updatedAt = now;
    }

    public void markFailed(String code, LocalDateTime now) {
        this.status = PayoutStatus.FAILED;
        this.failureCode = code;
        this.failedAt = now;
        this.updatedAt = now;
    }

    public void markReturned(String code, LocalDateTime now) {
        this.status = PayoutStatus.RETURNED;
        this.failureCode = code;
        this.returnedAt = now;
        this.updatedAt = now;
    }
}
