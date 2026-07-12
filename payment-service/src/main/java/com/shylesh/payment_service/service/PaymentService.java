package com.shylesh.payment_service.service;

import com.shylesh.payment_service.dto.CreatePaymentRequest;
import com.shylesh.payment_service.dto.PaymentResponse;

import java.util.UUID;

public interface PaymentService {

    PaymentResponse createPayment(String idempotencyKey, CreatePaymentRequest request);

    PaymentResponse getPayment(UUID paymentId);

    PaymentResponse processPayment(UUID id);

    PaymentResponse completePayment(UUID id);

    PaymentResponse failPayment(UUID id);

    PaymentResponse cancelPayment(UUID id);

    PaymentResponse refundPayment(UUID id);

}