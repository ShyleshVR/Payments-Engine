package com.shylesh.payment_service.service;

import com.shylesh.payment_service.dto.CreatePaymentRequest;
import com.shylesh.payment_service.dto.PaymentResponse;
import com.shylesh.payment_service.dto.SagaResponse;

import java.util.List;
import java.util.UUID;

public interface PaymentService {

    /** Accepts the payment and starts its saga; the outcome follows asynchronously (webhook or GET). */
    PaymentResponse createPayment(UUID merchantId, String idempotencyKey, CreatePaymentRequest request);

    PaymentResponse getPayment(UUID merchantId, UUID paymentId);

    /** MANUAL capture of an authorized payment. */
    PaymentResponse capturePayment(UUID merchantId, UUID paymentId);

    /** Releases an authorized, uncaptured payment. */
    PaymentResponse cancelPayment(UUID merchantId, UUID paymentId);

    PaymentResponse refundPayment(UUID merchantId, UUID paymentId);

    /** Operator: every saga of the payment, oldest first. */
    List<SagaResponse> getSagas(UUID paymentId);

    /** Operator: resume a saga parked in REQUIRES_ATTENTION. */
    List<SagaResponse> retrySaga(UUID paymentId);
}
