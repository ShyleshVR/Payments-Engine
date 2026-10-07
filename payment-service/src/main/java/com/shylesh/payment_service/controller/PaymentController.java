package com.shylesh.payment_service.controller;

import com.shylesh.payment_service.common.identifier.IdentifierService;
import com.shylesh.payment_service.dto.CreatePaymentRequest;
import com.shylesh.payment_service.dto.PaymentResponse;
import com.shylesh.payment_service.exception.InvalidIdempotencyKeyException;
import com.shylesh.payment_service.security.CurrentMerchant;
import com.shylesh.payment_service.service.PaymentService;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;

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

    @PostMapping("/{paymentId}/process")
    @PreAuthorize("hasAuthority(T(com.shylesh.payment_service.security.Scopes).PAYMENTS_OPERATE)")
    public ResponseEntity<PaymentResponse> processPayment(@PathVariable String paymentId) {

        UUID id = identifierService.parsePaymentId(paymentId);

        return ResponseEntity.ok(paymentService.processPayment(id));
    }

    @PostMapping("/{paymentId}/complete")
    @PreAuthorize("hasAuthority(T(com.shylesh.payment_service.security.Scopes).PAYMENTS_OPERATE)")
    public ResponseEntity<PaymentResponse> completePayment(@PathVariable String paymentId) {

        UUID id = identifierService.parsePaymentId(paymentId);

        return ResponseEntity.ok(paymentService.completePayment(id));
    }

    @PostMapping("/{paymentId}/fail")
    @PreAuthorize("hasAuthority(T(com.shylesh.payment_service.security.Scopes).PAYMENTS_OPERATE)")
    public ResponseEntity<PaymentResponse> failPayment(@PathVariable String paymentId) {

        UUID id = identifierService.parsePaymentId(paymentId);

        return ResponseEntity.ok(paymentService.failPayment(id));
    }

    @PostMapping("/{paymentId}/cancel")
    @PreAuthorize("hasAuthority(T(com.shylesh.payment_service.security.Scopes).PAYMENTS_WRITE)")
    public ResponseEntity<PaymentResponse> cancelPayment(@CurrentMerchant UUID merchantId, @PathVariable String paymentId) {

        UUID id = identifierService.parsePaymentId(paymentId);

        return ResponseEntity.ok(paymentService.cancelPayment(merchantId, id));
    }

    @PostMapping("/{paymentId}/refund")
    @PreAuthorize("hasAuthority(T(com.shylesh.payment_service.security.Scopes).PAYMENTS_WRITE)")
    public ResponseEntity<PaymentResponse> refundPayment(@CurrentMerchant UUID merchantId, @PathVariable String paymentId) {

        UUID id = identifierService.parsePaymentId(paymentId);

        return ResponseEntity.ok(paymentService.refundPayment(merchantId, id));
    }
}
