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

    @Column(name = "request_hash", length = 64, updatable = false)
    private String requestHash;

    /** Test payment method token (e.g. pm_card_visa); null for payments made before sagas. */
    @Column(name = "payment_method", length = 64, updatable = false)
    private String paymentMethod;

    @Enumerated(EnumType.STRING)
    @Column(name = "capture_method", nullable = false, length = 20, updatable = false)
    @Builder.Default
    private CaptureMethod captureMethod = CaptureMethod.AUTOMATIC;

    @Column(name = "failure_code", length = 64)
    private String failureCode;

    @Column(name = "refund_failure_code", length = 64)
    private String refundFailureCode;

    @Column(name = "processor_authorization_id", length = 40)
    private String processorAuthorizationId;

    @Column(name = "authorization_expires_at")
    private LocalDateTime authorizationExpiresAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Version
    @Column(nullable = false)
    private Long version;

    // Transitions, driven by the payment and refund sagas.

    /** Authorized at the processor. AUTOMATIC capture continues at once; MANUAL waits for the merchant. */
    public void recordAuthorization(String authorizationId, LocalDateTime expiresAt) {
        requireStatus(PaymentStatus.PROCESSING, PaymentStatus.AUTHORIZED);
        this.processorAuthorizationId = authorizationId;
        if (captureMethod == CaptureMethod.MANUAL) {
            this.status = PaymentStatus.AUTHORIZED;
            this.authorizationExpiresAt = expiresAt;
        }
    }

    /** MANUAL capture requested by the merchant. */
    public void markCaptureRequested() {
        transitionTo(PaymentStatus.AUTHORIZED, PaymentStatus.PROCESSING);
    }

    public void markSucceeded() {
        transitionTo(PaymentStatus.PROCESSING, PaymentStatus.SUCCESS);
    }

    public void markFailed(String code) {
        requireStatus(PaymentStatus.PROCESSING, PaymentStatus.FAILED);
        this.status = PaymentStatus.FAILED;
        this.failureCode = code;
    }

    public void markCancelled(String code) {
        requireStatus(PaymentStatus.AUTHORIZED, PaymentStatus.CANCELLED);
        this.status = PaymentStatus.CANCELLED;
        this.failureCode = code;
    }

    public void markRefundPending() {
        transitionTo(PaymentStatus.SUCCESS, PaymentStatus.REFUND_PENDING);
        this.refundFailureCode = null;
    }

    public void markRefunded() {
        transitionTo(PaymentStatus.REFUND_PENDING, PaymentStatus.REFUNDED);
    }

    /** The refund didn't happen: the payment is SUCCESS again, and can be refunded again later. */
    public void markRefundFailed(String code) {
        transitionTo(PaymentStatus.REFUND_PENDING, PaymentStatus.SUCCESS);
        this.refundFailureCode = code;
    }

    private void transitionTo(PaymentStatus expectedCurrent, PaymentStatus newStatus) {
        requireStatus(expectedCurrent, newStatus);
        this.status = newStatus;
    }

    private void requireStatus(PaymentStatus expectedCurrent, PaymentStatus newStatus) {
        if (this.status != expectedCurrent) {
            throw new InvalidPaymentStateException(status, newStatus);
        }
    }
}
