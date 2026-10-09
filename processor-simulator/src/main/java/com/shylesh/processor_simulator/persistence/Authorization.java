package com.shylesh.processor_simulator.persistence;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Funds reserved on a card: captured (charged) or voided (released) later. */
@Entity
@Table(name = "authorizations")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Authorization {

    @Id
    @Column(nullable = false, updatable = false, length = 40)
    private String id;

    @Column(name = "payment_method", nullable = false, updatable = false, length = 64)
    private String paymentMethod;

    @Column(nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AuthorizationStatus status;

    @Column(name = "captured_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal capturedAmount;

    @Column(name = "refunded_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal refundedAmount;

    @Column(length = 100, updatable = false)
    private String reference;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public void capture(LocalDateTime now) {
        this.status = AuthorizationStatus.CAPTURED;
        this.capturedAmount = amount;
        this.updatedAt = now;
    }

    public void voidAuthorization(LocalDateTime now) {
        this.status = AuthorizationStatus.VOIDED;
        this.updatedAt = now;
    }

    public BigDecimal refundable() {
        return capturedAmount.subtract(refundedAmount);
    }

    public void addRefund(BigDecimal refundAmount, LocalDateTime now) {
        this.refundedAmount = refundedAmount.add(refundAmount);
        this.updatedAt = now;
    }
}
