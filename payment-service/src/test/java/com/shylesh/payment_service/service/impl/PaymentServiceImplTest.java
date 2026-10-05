package com.shylesh.payment_service.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shylesh.payment_service.common.identifier.IdGenerator;
import com.shylesh.payment_service.common.outbox.OutboxEventFactory;
import com.shylesh.payment_service.common.outbox.OutboxEventRepository;
import com.shylesh.payment_service.dto.CreatePaymentRequest;
import com.shylesh.payment_service.dto.PaymentResponse;
import com.shylesh.payment_service.entity.Payment;
import com.shylesh.payment_service.entity.PaymentStatus;
import com.shylesh.payment_service.event.PaymentEventFactory;
import com.shylesh.payment_service.exception.IdempotencyKeyReuseException;
import com.shylesh.payment_service.mapper.PaymentMapperImpl;
import com.shylesh.payment_service.repository.PaymentRepository;
import com.shylesh.payment_service.service.IdempotencyService;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PaymentServiceImplTest {

    private static final String KEY = "order-42";

    private PaymentRepository paymentRepository;
    private IdempotencyService idempotencyService;
    private OutboxEventRepository outboxEventRepository;
    private PlatformTransactionManager transactionManager;
    private IdGenerator idGenerator;
    private RequestFingerprint fingerprint;
    private PaymentServiceImpl service;

    private UUID merchantId;
    private CreatePaymentRequest request;

    @BeforeEach
    void setUp() {
        paymentRepository = mock(PaymentRepository.class);
        idempotencyService = mock(IdempotencyService.class);
        outboxEventRepository = mock(OutboxEventRepository.class);
        transactionManager = mock(PlatformTransactionManager.class);
        idGenerator = mock(IdGenerator.class);
        fingerprint = new RequestFingerprint();

        service = new PaymentServiceImpl(
                paymentRepository,
                new PaymentMapperImpl(),
                idGenerator,
                idempotencyService,
                outboxEventRepository,
                new OutboxEventFactory(new ObjectMapper().findAndRegisterModules()),
                new PaymentEventFactory(),
                new SimpleMeterRegistry(),
                fingerprint,
                new TransactionTemplate(transactionManager)
        );

        merchantId = UUID.randomUUID();
        request = CreatePaymentRequest.builder()
                .amount(new BigDecimal("50.00"))
                .currency("USD")
                .merchantId(merchantId)
                .build();

        when(idGenerator.generate()).thenAnswer(invocation -> UUID.randomUUID());
        when(idempotencyService.get(any(), anyString())).thenReturn(Optional.empty());
        when(paymentRepository.findByMerchantIdAndIdempotencyKey(any(), anyString())).thenReturn(Optional.empty());
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private Payment existingPayment(String requestHash) {
        return Payment.builder()
                .id(UUID.randomUUID())
                .amount(new BigDecimal("50.00"))
                .currency("USD")
                .merchantId(merchantId)
                .status(PaymentStatus.CREATED)
                .idempotencyKey(KEY)
                .requestHash(requestHash)
                .build();
    }

    @Test
    void createsPaymentWithFingerprintAndCachesKeyOnlyAfterCommit() {
        PaymentResponse response = service.createPayment(KEY, request);

        assertThat(response.getStatus()).isEqualTo("CREATED");
        verify(paymentRepository).saveAndFlush(argThat(p -> fingerprint.of(request).equals(p.getRequestHash())));
        verify(outboxEventRepository).save(any());

        var inOrder = inOrder(transactionManager, idempotencyService);
        inOrder.verify(transactionManager).commit(any());
        inOrder.verify(idempotencyService).put(eq(merchantId), eq(KEY), anyString());
    }

    @Test
    void replaysExistingPaymentForSameKeyAndSameBody() {
        Payment existing = existingPayment(fingerprint.of(request));
        when(paymentRepository.findByMerchantIdAndIdempotencyKey(merchantId, KEY)).thenReturn(Optional.of(existing));

        PaymentResponse response = service.createPayment(KEY, request);

        assertThat(response.getPaymentId()).isEqualTo("pay_" + existing.getId());
        verify(paymentRepository, never()).saveAndFlush(any());
        verifyNoInteractions(outboxEventRepository);
    }

    @Test
    void rejectsSameKeyReusedWithDifferentBody() {
        Payment existing = existingPayment(fingerprint.of(request));
        when(paymentRepository.findByMerchantIdAndIdempotencyKey(merchantId, KEY)).thenReturn(Optional.of(existing));

        CreatePaymentRequest different = CreatePaymentRequest.builder()
                .amount(new BigDecimal("999.00"))
                .currency("EUR")
                .merchantId(merchantId)
                .build();

        assertThatThrownBy(() -> service.createPayment(KEY, different))
                .isInstanceOf(IdempotencyKeyReuseException.class);
        verify(paymentRepository, never()).saveAndFlush(any());
    }

    @Test
    void replaysLegacyPaymentWithoutFingerprintWithoutComparingBodies() {
        Payment legacy = existingPayment(null);
        when(paymentRepository.findByMerchantIdAndIdempotencyKey(merchantId, KEY)).thenReturn(Optional.of(legacy));

        PaymentResponse response = service.createPayment(KEY, request);

        assertThat(response.getPaymentId()).isEqualTo("pay_" + legacy.getId());
    }

    @Test
    void ignoresCacheEntryPointingAtMissingPaymentInsteadOf404() {
        when(idempotencyService.get(merchantId, KEY)).thenReturn(Optional.of(UUID.randomUUID().toString()));
        when(paymentRepository.findById(any())).thenReturn(Optional.empty());

        PaymentResponse response = service.createPayment(KEY, request);

        assertThat(response.getStatus()).isEqualTo("CREATED");
        verify(paymentRepository).saveAndFlush(any());
    }

    @Test
    void concurrentInsertWithSameKeyIsAnsweredAsReplayOfTheWinner() {
        Payment winner = existingPayment(fingerprint.of(request));
        when(paymentRepository.findByMerchantIdAndIdempotencyKey(merchantId, KEY))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        when(paymentRepository.saveAndFlush(any(Payment.class)))
                .thenThrow(new DataIntegrityViolationException("uk_payment_merchant_idempotency_key"));

        PaymentResponse response = service.createPayment(KEY, request);

        assertThat(response.getPaymentId()).isEqualTo("pay_" + winner.getId());
        verify(transactionManager).rollback(any());
        verify(idempotencyService, never()).put(any(), anyString(), anyString());
    }

    @Test
    void rethrowsIntegrityViolationThatIsNotAnIdempotencyRace() {
        DataIntegrityViolationException violation = new DataIntegrityViolationException("some other constraint");
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenThrow(violation);

        assertThatThrownBy(() -> service.createPayment(KEY, request)).isSameAs(violation);
    }
}
