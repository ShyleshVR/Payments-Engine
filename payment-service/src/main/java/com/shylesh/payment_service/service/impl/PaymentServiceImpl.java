package com.shylesh.payment_service.service.impl;

import com.shylesh.payment_service.dto.CreatePaymentRequest;
import com.shylesh.payment_service.dto.PaymentResponse;
import com.shylesh.payment_service.dto.PaymentAuditView;
import com.shylesh.payment_service.dto.SagaResponse;
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
import com.shylesh.payment_service.exception.InvalidPaymentStateException;
import com.shylesh.payment_service.exception.PaymentNotFoundException;
import com.shylesh.payment_service.common.tracing.TraceContext;
import com.shylesh.payment_service.saga.PaymentSaga;
import com.shylesh.payment_service.saga.PaymentSagaRepository;
import com.shylesh.payment_service.saga.PaymentSagaStepRepository;
import com.shylesh.payment_service.saga.SagaOrchestrator;
import com.shylesh.payment_service.event.PaymentCreatedEvent;
import com.shylesh.payment_service.event.PaymentEventFactory;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
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
    private final SagaOrchestrator sagaOrchestrator;
    private final PaymentSagaRepository sagaRepository;
    private final PaymentSagaStepRepository sagaStepRepository;
    private final TraceContext traceContext;

    /**
     * Not @Transactional on purpose: the insert runs in its own transaction (via
     * transactionTemplate) so that a unique-constraint violation from a concurrent request with
     * the same key can be caught here and turned into a replay, and so the idempotency cache is
     * only written once the payment has actually committed.
     */
    @Override
    public PaymentResponse createPayment(UUID merchantId, String idempotencyKey, CreatePaymentRequest request) {

        String requestHash = requestFingerprint.of(merchantId, request);

        Optional<Payment> existingPayment = findByIdempotencyKey(merchantId, idempotencyKey);
        if (existingPayment.isPresent()) {
            return replay(existingPayment.get(), idempotencyKey, requestHash);
        }

        Payment savedPayment;
        try {
            savedPayment = transactionTemplate.execute(status -> insertPayment(merchantId, idempotencyKey, requestHash, request));
        } catch (DataIntegrityViolationException e) {
            // A concurrent request with the same key committed first; answer as its replay.
            Payment winner = paymentRepository
                    .findByMerchantIdAndIdempotencyKey(merchantId, idempotencyKey)
                    .orElseThrow(() -> e);
            return replay(winner, idempotencyKey, requestHash);
        }

        idempotencyService.put(merchantId, idempotencyKey, savedPayment.getId().toString());

        Counter.builder("payments.created")
                .tag("currency", request.getCurrency())
                .register(meterRegistry)
                .increment();

        DistributionSummary.builder("payments.amount")
                .tag("currency", request.getCurrency())
                .serviceLevelObjectives(10, 50, 100, 500, 1000, 5000, 10000, 50000)
                .register(meterRegistry)
                .record(request.getAmount().doubleValue());

        recordStatusTransition(PaymentStatus.PROCESSING);

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

    private Payment insertPayment(UUID merchantId, String idempotencyKey, String requestHash, CreatePaymentRequest request) {
        Payment payment = Payment.builder()
                .id(idGenerator.generate())
                .amount(request.getAmount())
                .currency(request.getCurrency())
                .merchantId(merchantId)
                .customerId(request.getCustomerId())
                .description(request.getDescription())
                .paymentMethod(request.getPaymentMethod())
                .captureMethod(request.captureMethodOrDefault())
                .status(PaymentStatus.PROCESSING)
                .idempotencyKey(idempotencyKey)
                .requestHash(requestHash)
                .build();

        Payment savedPayment = paymentRepository.saveAndFlush(payment);

        PaymentCreatedEvent event = paymentEventFactory.create(savedPayment);
        outboxEventRepository.save(outboxEventFactory.createPaymentCreatedEvent(event, traceContext.current()));

        // same transaction: a payment never exists without the saga that completes it
        sagaOrchestrator.startPayment(savedPayment);

        return savedPayment;
    }

    /**
     * Another merchant's payment is reported as not found, exactly like a payment that doesn't
     * exist, so ids can't be probed across merchants.
     */
    private Payment findOwned(UUID merchantId, UUID paymentId) {
        return paymentRepository.findByIdAndMerchantId(paymentId, merchantId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));
    }

    private void recordStatusTransition(PaymentStatus status) {
        Counter.builder("payments.status.transitions")
                .tag("status", status.name())
                .register(meterRegistry)
                .increment();
    }

    @Override
    public PaymentResponse getPayment(UUID merchantId, UUID paymentId) {

        Payment payment = findOwned(merchantId, paymentId);

        return paymentMapper.toResponse(payment);
    }

    @Override
    public PaymentResponse capturePayment(UUID merchantId, UUID paymentId) {
        sagaOrchestrator.requestCapture(merchantId, paymentId);
        return getPayment(merchantId, paymentId);
    }

    @Override
    public PaymentResponse cancelPayment(UUID merchantId, UUID paymentId) {
        sagaOrchestrator.requestCancel(merchantId, paymentId);
        return getPayment(merchantId, paymentId);
    }

    @Override
    public PaymentResponse refundPayment(UUID merchantId, UUID paymentId) {
        try {
            sagaOrchestrator.startRefund(merchantId, paymentId);
        } catch (DataIntegrityViolationException | ObjectOptimisticLockingFailureException e) {
            // a concurrent refund request of the same payment won (one active saga per payment)
            throw new InvalidPaymentStateException("A refund of this payment is already in progress");
        }
        return getPayment(merchantId, paymentId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SagaResponse> getSagas(UUID paymentId) {
        if (!paymentRepository.existsById(paymentId)) {
            throw new PaymentNotFoundException(paymentId);
        }
        return sagaRepository.findByPaymentIdOrderByCreatedAtAsc(paymentId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    public List<SagaResponse> retrySaga(UUID paymentId) {
        sagaOrchestrator.retry(paymentId);
        return getSagas(paymentId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PaymentAuditView> lookupPayments(List<UUID> paymentIds) {
        java.util.Set<UUID> inFlight = new java.util.HashSet<>(sagaRepository.findPaymentIdsWithActiveSaga(paymentIds));
        return paymentRepository.findAllById(paymentIds).stream()
                .map(p -> new PaymentAuditView("pay_" + p.getId(), p.getStatus().name(), p.getAmount(), p.getCurrency(),
                        p.getCaptureMethod() == null ? null : p.getCaptureMethod().name(),
                        p.getFailureCode(), p.getRefundFailureCode(), inFlight.contains(p.getId()),
                        p.getPaymentMethod() != null,
                        p.getCreatedAt(), p.getUpdatedAt()))
                .toList();
    }

    private SagaResponse toResponse(PaymentSaga saga) {
        List<SagaResponse.Step> steps = sagaStepRepository.findBySagaIdOrderByIdAsc(saga.getId()).stream()
                .map(step -> new SagaResponse.Step(step.getState().name(), step.getOutcome(), step.getDetail(), step.getOccurredAt()))
                .toList();
        return new SagaResponse(saga.getId(), saga.getType().name(), saga.getState().name(),
                saga.getStuckState() == null ? null : saga.getStuckState().name(),
                saga.getAttempt(), saga.getNextAttemptAt(), saga.getStepStartedAt(), saga.getFailureCode(),
                saga.getLastError(), saga.getCreatedAt(), saga.getFinishedAt(), steps);
    }
}
