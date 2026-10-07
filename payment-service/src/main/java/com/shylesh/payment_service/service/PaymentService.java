package com.shylesh.payment_service.service;

import com.shylesh.payment_service.dto.CreatePaymentRequest;
import com.shylesh.payment_service.dto.PaymentResponse;

import java.util.UUID;

/**
 * Merchant operations (create, get, cancel, refund) take the caller's merchant id and only ever
 * see that merchant's payments. process/complete/fail are internal operations on any payment.
 */
public interface PaymentService {

    PaymentResponse createPayment(UUID merchantId, String idempotencyKey, CreatePaymentRequest request);

    PaymentResponse getPayment(UUID merchantId, UUID paymentId);

    PaymentResponse processPayment(UUID id);

    PaymentResponse completePayment(UUID id);

    PaymentResponse failPayment(UUID id);

    PaymentResponse cancelPayment(UUID merchantId, UUID id);

    PaymentResponse refundPayment(UUID merchantId, UUID id);

}