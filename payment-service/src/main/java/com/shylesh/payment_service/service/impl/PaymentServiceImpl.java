package com.shylesh.payment_service.service.impl;

import com.shylesh.payment_service.dto.CreatePaymentRequest;
import com.shylesh.payment_service.dto.PaymentResponse;
import com.shylesh.payment_service.entity.Payment;
import com.shylesh.payment_service.entity.PaymentStatus;
import com.shylesh.payment_service.mapper.PaymentMapper;
import com.shylesh.payment_service.repository.PaymentRepository;
import com.shylesh.payment_service.service.IdempotencyService;
import com.shylesh.payment_service.service.PaymentService;
import com.shylesh.payment_service.common.identifier.IdGenerator;
import com.shylesh.payment_service.common.outbox.OutboxEvent;
import com.shylesh.payment_service.common.outbox.OutboxEventFactory;
import com.shylesh.payment_service.common.outbox.OutboxEventRepository;
import com.shylesh.payment_service.exception.PaymentNotFoundException;
import com.shylesh.payment_service.event.PaymentCreatedEvent;
import com.shylesh.payment_service.event.PaymentEventFactory;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;


@Service
@RequiredArgsConstructor
public class PaymentServiceImpl implements PaymentService {

    private final PaymentRepository paymentRepository;
    private final PaymentMapper paymentMapper;
    private final IdGenerator idGenerator;
    private final IdempotencyService  idempotencyService;
    private final OutboxEventRepository outboxEventRepository;
    private final OutboxEventFactory outboxEventFactory;
    private final PaymentEventFactory paymentEventFactory;

    @Transactional
    @Override
    public PaymentResponse createPayment(String idempotencyKey, CreatePaymentRequest request) {

        Optional<String> existingPaymentId = idempotencyService.get(idempotencyKey);

        if(existingPaymentId.isPresent()) {
            UUID paymentId = UUID.fromString(existingPaymentId.get());
            Payment existingPayment = paymentRepository.findById(paymentId)
                    .orElseThrow(() -> new PaymentNotFoundException(paymentId));
            return paymentMapper.toResponse(existingPayment);
        }

        Optional<Payment> existingPayment = paymentRepository.findByIdempotencyKey(idempotencyKey);
        if (existingPayment.isPresent()) {
            idempotencyService.put(idempotencyKey, existingPayment.get().getId().toString());   
            return paymentMapper.toResponse(existingPayment.get());
        }

        UUID paymentId = idGenerator.generate();

        Payment payment = Payment.builder()
                .id(paymentId)
                .amount(request.getAmount())
                .currency(request.getCurrency())
                .merchantId(request.getMerchantId())
                .customerId(request.getCustomerId())
                .description(request.getDescription())
                .status(PaymentStatus.CREATED)
                .idempotencyKey(idempotencyKey)
                .build();

        Payment savedPayment = paymentRepository.saveAndFlush(payment);
        idempotencyService.put(idempotencyKey, paymentId.toString());

        PaymentCreatedEvent event = paymentEventFactory.create(savedPayment);

        OutboxEvent outboxEvent =
                outboxEventFactory.createPaymentCreatedEvent(event);

        outboxEventRepository.save(outboxEvent);

        return paymentMapper.toResponse(savedPayment);
    }

    @Override
    public PaymentResponse getPayment(UUID paymentId) {

        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));

        return paymentMapper.toResponse(payment);
    }

    @Override
    public PaymentResponse processPayment(UUID id) {
        Payment payment = paymentRepository.findById(id)
                .orElseThrow(() -> new PaymentNotFoundException(id));

        payment.markProcessing();
        Payment updatedPayment = paymentRepository.save(payment);

        return paymentMapper.toResponse(updatedPayment);
    }

    @Override
    public PaymentResponse completePayment(UUID id) {
        Payment payment = paymentRepository.findById(id)
                .orElseThrow(() -> new PaymentNotFoundException(id));

        payment.markSuccessful();
        Payment updatedPayment = paymentRepository.save(payment);

        return paymentMapper.toResponse(updatedPayment);
    }

    @Override
    public PaymentResponse failPayment(UUID id) {
        Payment payment = paymentRepository.findById(id)
                .orElseThrow(() -> new PaymentNotFoundException(id));

        payment.markFailed();
        Payment updatedPayment = paymentRepository.save(payment);

        return paymentMapper.toResponse(updatedPayment);
    }

    @Override
    public PaymentResponse cancelPayment(UUID id) {
        Payment payment = paymentRepository.findById(id)
                .orElseThrow(() -> new PaymentNotFoundException(id));

        payment.markCancelled();
        Payment updatedPayment = paymentRepository.save(payment);

        return paymentMapper.toResponse(updatedPayment);
    }

    @Override
    public PaymentResponse refundPayment(UUID id) {
        Payment payment = paymentRepository.findById(id)
                .orElseThrow(() -> new PaymentNotFoundException(id));

        payment.markRefunded();
        Payment updatedPayment = paymentRepository.save(payment);

        return paymentMapper.toResponse(updatedPayment);
    }
}