package com.shylesh.payment_service.controller;

import com.shylesh.payment_service.common.identifier.IdentifierService;
import com.shylesh.payment_service.dto.CreatePaymentRequest;
import com.shylesh.payment_service.dto.PaymentResponse;
import com.shylesh.payment_service.dto.PaymentAuditView;
import com.shylesh.payment_service.dto.PaymentLookupRequest;
import com.shylesh.payment_service.dto.SagaResponse;
import com.shylesh.payment_service.exception.InvalidIdempotencyKeyException;
import com.shylesh.payment_service.security.CurrentMerchant;
import com.shylesh.payment_service.service.PaymentService;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;

import java.util.List;
import java.util.UUID;

/**
 * Merchant endpoints (create, get, cancel, refund) act only on the calling merchant's payments.
 * process/complete/fail are processing outcomes, not merchant actions: they need the internal
 * payments:operate scope until a payment processor integration drives them.
 */
@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 255;

    private final PaymentService paymentService;
    private final IdentifierService identifierService;

    @PostMapping
    @PreAuthorize("hasAuthority(T(com.shylesh.payment_service.security.Scopes).PAYMENTS_WRITE)")
    public ResponseEntity<PaymentResponse> createPayment(@CurrentMerchant UUID merchantId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreatePaymentRequest request) {

        if (idempotencyKey.isBlank() || idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new InvalidIdempotencyKeyException(
                    "Idempotency-Key must be 1-" + MAX_IDEMPOTENCY_KEY_LENGTH + " characters");
        }

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(paymentService.createPayment(merchantId, idempotencyKey, request));
    }

    @GetMapping("/{paymentId}")
    @PreAuthorize("hasAuthority(T(com.shylesh.payment_service.security.Scopes).PAYMENTS_READ)")
    public ResponseEntity<PaymentResponse> getPayment(@CurrentMerchant UUID merchantId, @PathVariable String paymentId) {

        UUID id = identifierService.parsePaymentId(paymentId);

        return ResponseEntity.ok(paymentService.getPayment(merchantId, id));
    }

    /** MANUAL capture: charge an authorized payment. The saga completes it asynchronously. */
    @PostMapping("/{paymentId}/capture")
    @PreAuthorize("hasAuthority(T(com.shylesh.payment_service.security.Scopes).PAYMENTS_WRITE)")
    public ResponseEntity<PaymentResponse> capturePayment(@CurrentMerchant UUID merchantId, @PathVariable String paymentId) {

        UUID id = identifierService.parsePaymentId(paymentId);

        return ResponseEntity.accepted().body(paymentService.capturePayment(merchantId, id));
    }

    /** Releases an authorized, uncaptured payment; it becomes CANCELLED once the processor confirms. */
    @PostMapping("/{paymentId}/cancel")
    @PreAuthorize("hasAuthority(T(com.shylesh.payment_service.security.Scopes).PAYMENTS_WRITE)")
    public ResponseEntity<PaymentResponse> cancelPayment(@CurrentMerchant UUID merchantId, @PathVariable String paymentId) {

        UUID id = identifierService.parsePaymentId(paymentId);

        return ResponseEntity.accepted().body(paymentService.cancelPayment(merchantId, id));
    }

    /** Starts a refund (REFUND_PENDING); the outcome arrives as PAYMENT_REFUNDED or PAYMENT_REFUND_FAILED. */
    @PostMapping("/{paymentId}/refund")
    @PreAuthorize("hasAuthority(T(com.shylesh.payment_service.security.Scopes).PAYMENTS_WRITE)")
    public ResponseEntity<PaymentResponse> refundPayment(@CurrentMerchant UUID merchantId, @PathVariable String paymentId) {

        UUID id = identifierService.parsePaymentId(paymentId);

        return ResponseEntity.accepted().body(paymentService.refundPayment(merchantId, id));
    }

    /**
     * Audit: many payments at once, by public id (unknown ids are left out). Used by the daily
     * reconciliation; read-only, so the audit scope suffices.
     */
    @PostMapping("/lookup")
    @PreAuthorize("hasAnyAuthority(T(com.shylesh.payment_service.security.Scopes).PAYMENTS_AUDIT, T(com.shylesh.payment_service.security.Scopes).PAYMENTS_OPERATE)")
    public ResponseEntity<List<PaymentAuditView>> lookupPayments(@Valid @RequestBody PaymentLookupRequest request) {

        List<UUID> ids = request.paymentIds().stream().map(identifierService::parsePaymentId).distinct().toList();

        return ResponseEntity.ok(paymentService.lookupPayments(ids));
    }

    /** Operator: the payment's sagas with their step history. */
    @GetMapping("/{paymentId}/saga")
    @PreAuthorize("hasAuthority(T(com.shylesh.payment_service.security.Scopes).PAYMENTS_OPERATE)")
    public ResponseEntity<List<SagaResponse>> getSagas(@PathVariable String paymentId) {

        UUID id = identifierService.parsePaymentId(paymentId);

        return ResponseEntity.ok(paymentService.getSagas(id));
    }

    /** Operator: resume a saga parked in REQUIRES_ATTENTION (after fixing what stopped it). */
    @PostMapping("/{paymentId}/saga/retry")
    @PreAuthorize("hasAuthority(T(com.shylesh.payment_service.security.Scopes).PAYMENTS_OPERATE)")
    public ResponseEntity<List<SagaResponse>> retrySaga(@PathVariable String paymentId) {

        UUID id = identifierService.parsePaymentId(paymentId);

        return ResponseEntity.ok(paymentService.retrySaga(id));
    }
}
