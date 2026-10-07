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
import com.shylesh.payment_service.common.outbox.OutboxEventFactory;
import com.shylesh.payment_service.common.outbox.OutboxEventRepository;
import com.shylesh.payment_service.exception.IdempotencyKeyReuseException;
import com.shylesh.payment_service.exception.PaymentNotFoundException;
import com.shylesh.payment_service.event.PaymentCreatedEvent;
import com.shylesh.payment_service.event.PaymentEventFactory;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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
    private final MeterRegistry meterRegistry;
    private final RequestFingerprint requestFingerprint;
    private final TransactionTemplate transactionTemplate;

    /**
     * Not @Transactional on purpose: the insert runs in its own transaction (via
     * transactionTemplate) so that a unique-constraint violation from a concurrent request with
     * the same key can be caught here and turned into a replay, and so the idempotency cache is
     * only written once the payment has actually committed.
     */
    @Override
    public PaymentResponse createPayment(String idempotencyKey, CreatePaymentRequest request) {

        String requestHash = requestFingerprint.of(request);

        Optional<Payment> existingPayment = findByIdempotencyKey(request.getMerchantId(), idempotencyKey);
        if (existingPayment.isPresent()) {
            return replay(existingPayment.get(), idempotencyKey, requestHash);
        }

        Payment savedPayment;
        try {
            savedPayment = transactionTemplate.execute(status -> insertPayment(idempotencyKey, requestHash, request));
        } catch (DataIntegrityViolationException e) {
            // A concurrent request with the same key committed first; answer as its replay.
            Payment winner = paymentRepository
                    .findByMerchantIdAndIdempotencyKey(request.getMerchantId(), idempotencyKey)
                    .orElseThrow(() -> e);
            return replay(winner, idempotencyKey, requestHash);
        }

        idempotencyService.put(request.getMerchantId(), idempotencyKey, savedPayment.getId().toString());

        Counter.builder("payments.created")
                .tag("currency", request.getCurrency())
                .register(meterRegistry)
                .increment();

        DistributionSummary.builder("payments.amount")
                .tag("currency", request.getCurrency())
                .serviceLevelObjectives(10, 50, 100, 500, 1000, 5000, 10000, 50000)
                .register(meterRegistry)
                .record(request.getAmount().doubleValue());

        recordStatusTransition(PaymentStatus.CREATED);

        return paymentMapper.toResponse(savedPayment);
    }

    /**
     * Cache first, database second. A cache entry pointing at a payment that doesn't exist is
     * ignored rather than trusted, so a stale entry can never turn a retry into a 404.
     */
    private Optional<Payment> findByIdempotencyKey(UUID merchantId, String idempotencyKey) {
        Optional<Payment> cached = idempotencyService.get(merchantId, idempotencyKey)
                .flatMap(paymentId -> paymentRepository.findById(UUID.fromString(paymentId)));
        if (cached.isPresent()) {
            return cached;
        }

        Optional<Payment> stored = paymentRepository.findByMerchantIdAndIdempotencyKey(merchantId, idempotencyKey);
        stored.ifPresent(payment -> idempotencyService.put(merchantId, idempotencyKey, payment.getId().toString()));
        return stored;
    }

    private PaymentResponse replay(Payment payment, String idempotencyKey, String requestHash) {
        if (payment.getRequestHash() != null && !payment.getRequestHash().equals(requestHash)) {
            throw new IdempotencyKeyReuseException(idempotencyKey);
        }
        return paymentMapper.toResponse(payment);
    }

    private Payment insertPayment(String idempotencyKey, String requestHash, CreatePaymentRequest request) {
        Payment payment = Payment.builder()
                .id(idGenerator.generate())
                .amount(request.getAmount())
                .currency(request.getCurrency())
                .merchantId(request.getMerchantId())
                .customerId(request.getCustomerId())
                .description(request.getDescription())
                .status(PaymentStatus.CREATED)
                .idempotencyKey(idempotencyKey)
                .requestHash(requestHash)
                .build();

        Payment savedPayment = paymentRepository.saveAndFlush(payment);

        PaymentCreatedEvent event = paymentEventFactory.create(savedPayment);
        outboxEventRepository.save(outboxEventFactory.createPaymentCreatedEvent(event));

        return savedPayment;
    }

    private void recordStatusTransition(PaymentStatus status) {
        Counter.builder("payments.status.transitions")
                .tag("status", status.name())
                .register(meterRegistry)
                .increment();
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
        recordStatusTransition(updatedPayment.getStatus());

        return paymentMapper.toResponse(updatedPayment);
    }

    @Transactional
    @Override
    public PaymentResponse completePayment(UUID id) {
        Payment payment = paymentRepository.findById(id)
                .orElseThrow(() -> new PaymentNotFoundException(id));

        payment.markSuccessful();
        Payment updatedPayment = paymentRepository.save(payment);
        recordStatusTransition(updatedPayment.getStatus());

        PaymentCreatedEvent event = paymentEventFactory.create(updatedPayment);
        outboxEventRepository.save(outboxEventFactory.createPaymentCompletedEvent(event));

        return paymentMapper.toResponse(updatedPayment);
    }

    @Transactional
    @Override
    public PaymentResponse failPayment(UUID id) {
        Payment payment = paymentRepository.findById(id)
                .orElseThrow(() -> new PaymentNotFoundException(id));

        payment.markFailed();
        Payment updatedPayment = paymentRepository.save(payment);
        recordStatusTransition(updatedPayment.getStatus());

        PaymentCreatedEvent event = paymentEventFactory.create(updatedPayment);
        outboxEventRepository.save(outboxEventFactory.createPaymentFailedEvent(event));

        return paymentMapper.toResponse(updatedPayment);
    }

    @Override
    public PaymentResponse cancelPayment(UUID id) {
        Payment payment = paymentRepository.findById(id)
                .orElseThrow(() -> new PaymentNotFoundException(id));

        payment.markCancelled();
        Payment updatedPayment = paymentRepository.save(payment);
        recordStatusTransition(updatedPayment.getStatus());

        return paymentMapper.toResponse(updatedPayment);
    }

    @Transactional
    @Override
    public PaymentResponse refundPayment(UUID id) {
        Payment payment = paymentRepository.findById(id)
                .orElseThrow(() -> new PaymentNotFoundException(id));

        payment.markRefunded();
        Payment updatedPayment = paymentRepository.save(payment);
        recordStatusTransition(updatedPayment.getStatus());

        PaymentCreatedEvent event = paymentEventFactory.create(updatedPayment);
        outboxEventRepository.save(outboxEventFactory.createPaymentRefundedEvent(event));

        return paymentMapper.toResponse(updatedPayment);
    }
}