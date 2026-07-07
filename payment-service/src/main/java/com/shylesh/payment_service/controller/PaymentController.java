package com.shylesh.payment_service.controller;

import com.shylesh.payment_service.common.identifier.IdentifierService;
import com.shylesh.payment_service.dto.CreatePaymentRequest;
import com.shylesh.payment_service.dto.PaymentResponse;
import com.shylesh.payment_service.service.PaymentService;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;
    private final IdentifierService identifierService;

    @PostMapping
    public ResponseEntity<PaymentResponse> createPayment(
            @Valid @RequestBody CreatePaymentRequest request) {

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(paymentService.createPayment(request));
    }

    @GetMapping("/{paymentId}")
    public ResponseEntity<PaymentResponse> getPayment(
            @PathVariable String paymentId) {

        UUID id = identifierService.parsePaymentId(paymentId);

        return ResponseEntity.ok(paymentService.getPayment(id));
    }

    @PostMapping("/{paymentId}/process")
    public ResponseEntity<PaymentResponse> processPayment(@PathVariable String paymentId) {

        UUID id = identifierService.parsePaymentId(paymentId);

        return ResponseEntity.ok(paymentService.processPayment(id));
    }

    @PostMapping("/{paymentId}/complete")
    public ResponseEntity<PaymentResponse> completePayment(@PathVariable String paymentId) {

        UUID id = identifierService.parsePaymentId(paymentId);

        return ResponseEntity.ok(paymentService.completePayment(id));
    }

    @PostMapping("/{paymentId}/fail")
    public ResponseEntity<PaymentResponse> failPayment(@PathVariable String paymentId) {
        
        UUID id = identifierService.parsePaymentId(paymentId);

        return ResponseEntity.ok(paymentService.failPayment(id));
    }

    @PostMapping("/{paymentId}/cancel")
    public ResponseEntity<PaymentResponse> cancelPayment(@PathVariable String paymentId) {
        
        UUID id = identifierService.parsePaymentId(paymentId);

        return ResponseEntity.ok(paymentService.cancelPayment(id));
    }
}
