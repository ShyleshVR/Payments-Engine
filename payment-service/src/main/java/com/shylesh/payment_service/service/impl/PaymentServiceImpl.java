package com.shylesh.payment_service.service.impl;

import com.shylesh.payment_service.dto.CreatePaymentRequest;
import com.shylesh.payment_service.dto.PaymentResponse;
import com.shylesh.payment_service.entity.Payment;
import com.shylesh.payment_service.entity.PaymentStatus;
import com.shylesh.payment_service.mapper.PaymentMapper;
import com.shylesh.payment_service.repository.PaymentRepository;
import com.shylesh.payment_service.service.PaymentService;
import com.shylesh.payment_service.common.identifier.IdGenerator;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.UUID;


@Service
@RequiredArgsConstructor
public class PaymentServiceImpl implements PaymentService {

    private final PaymentRepository paymentRepository;
    private final PaymentMapper paymentMapper;
    private final IdGenerator idGenerator;

    @Override
    public PaymentResponse createPayment(CreatePaymentRequest request) {

        UUID paymentId = idGenerator.generate();
        LocalDateTime now = LocalDateTime.now();

        Payment payment = Payment.builder()
                .id(paymentId)
                .amount(request.getAmount())
                .currency(request.getCurrency())
                .merchantId(request.getMerchantId())
                .customerId(request.getCustomerId())
                .description(request.getDescription())
                .status(PaymentStatus.CREATED)
                .createdAt(now)
                .updatedAt(now)
                .build();

        Payment savedPayment = paymentRepository.save(payment);

        return paymentMapper.toResponse(savedPayment);
    }
}