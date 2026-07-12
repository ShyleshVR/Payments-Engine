package com.shylesh.payment_service.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;

import com.shylesh.payment_service.exception.InvalidPaymentStateException;

@Entity
@Table(name = "payment")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class Payment {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "merchant_id", nullable = false, updatable = false)
    private UUID merchantId;

    @Column(name = "customer_id", updatable = false)
    private UUID customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    @Column(length = 255)
    private String description;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public void markProcessing() {
        transitionTo(PaymentStatus.CREATED, PaymentStatus.PROCESSING);
    }

    public void markSuccessful() {
        transitionTo(PaymentStatus.PROCESSING, PaymentStatus.SUCCESS);
    }

    public void markFailed() {
        transitionTo(PaymentStatus.PROCESSING, PaymentStatus.FAILED);
    }

    public void markCancelled() {
        transitionTo(PaymentStatus.CREATED, PaymentStatus.CANCELLED);
    }

    public void markRefunded() {
        transitionTo(PaymentStatus.SUCCESS, PaymentStatus.REFUNDED);
    }

    private void transitionTo(PaymentStatus expectedCurrent,PaymentStatus newStatus){
        if (this.status != expectedCurrent) {
            throw new InvalidPaymentStateException(status, newStatus);
        }

        this.status = newStatus;
    }
}