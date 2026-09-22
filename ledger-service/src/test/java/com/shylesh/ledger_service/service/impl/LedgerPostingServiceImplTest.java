package com.shylesh.ledger_service.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shylesh.ledger_service.event.EventEnvelope;
import com.shylesh.ledger_service.persistence.*;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

class LedgerPostingServiceImplTest {

    private ProcessedEventRepository processedEventRepository;
    private LedgerTransactionRepository transactionRepository;
    private LedgerEntryRepository entryRepository;
    private LedgerAccountResolver accountResolver;
    private MeterRegistry meterRegistry;
    private LedgerPostingServiceImpl service;

    private LedgerAccount platformAccount;
    private LedgerAccount merchantAccount;

    @BeforeEach
    void setUp() {
        processedEventRepository = mock(ProcessedEventRepository.class);
        transactionRepository = mock(LedgerTransactionRepository.class);
        entryRepository = mock(LedgerEntryRepository.class);
        accountResolver = mock(LedgerAccountResolver.class);
        meterRegistry = new SimpleMeterRegistry();

        service = new LedgerPostingServiceImpl(
                processedEventRepository,
                transactionRepository,
                entryRepository,
                accountResolver,
                new ObjectMapper().findAndRegisterModules(),
                meterRegistry
        );

        platformAccount = LedgerAccount.builder()
                .id(UUID.randomUUID())
                .ownerType(LedgerAccountType.PLATFORM_CLEARING)
                .ownerId(null)
                .currency("USD")
                .createdAt(LocalDateTime.now())
                .build();

        merchantAccount = LedgerAccount.builder()
                .id(UUID.randomUUID())
                .ownerType(LedgerAccountType.MERCHANT)
                .currency("USD")
                .createdAt(LocalDateTime.now())
                .build();

        when(accountResolver.resolve(eq(LedgerAccountType.PLATFORM_CLEARING), isNull(), eq("USD")))
                .thenReturn(platformAccount);
        when(accountResolver.resolve(eq(LedgerAccountType.MERCHANT), any(UUID.class), eq("USD")))
                .thenReturn(merchantAccount);

        when(transactionRepository.save(any(LedgerTransaction.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(entryRepository.saveAll(anyList()))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private EventEnvelope envelope(UUID eventId, String eventType, UUID paymentId, UUID merchantId) {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Map<String, Object> data = Map.of(
                "paymentId", paymentId.toString(),
                "amount", "150.00",
                "currency", "USD",
                "merchantId", merchantId.toString(),
                "customerId", UUID.randomUUID().toString(),
                "createdAt", LocalDateTime.now().toString()
        );
        return new EventEnvelope(eventId, eventType, LocalDateTime.now(), mapper.valueToTree(data));
    }

    @Test
    void settlementDebitsPlatformClearingAndCreditsMerchant() {
        UUID eventId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();

        when(processedEventRepository.existsById(eventId)).thenReturn(false);

        service.handle(envelope(eventId, "PAYMENT_COMPLETED", paymentId, merchantId));

        ArgumentCaptor<LedgerTransaction> txCaptor = ArgumentCaptor.forClass(LedgerTransaction.class);
        verify(transactionRepository).save(txCaptor.capture());
        assertThat(txCaptor.getValue().getType()).isEqualTo(LedgerTransactionType.SETTLEMENT);
        assertThat(txCaptor.getValue().getPaymentId()).isEqualTo(paymentId);
        assertThat(txCaptor.getValue().getEventId()).isEqualTo(eventId);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LedgerEntry>> entryCaptor = ArgumentCaptor.forClass(List.class);
        verify(entryRepository).saveAll(entryCaptor.capture());
        List<LedgerEntry> entries = entryCaptor.getValue();

        LedgerEntry debit = entries.stream().filter(e -> e.getDirection() == LedgerDirection.DEBIT).findFirst().orElseThrow();
        LedgerEntry credit = entries.stream().filter(e -> e.getDirection() == LedgerDirection.CREDIT).findFirst().orElseThrow();

        assertThat(debit.getAccountId()).isEqualTo(platformAccount.getId());
        assertThat(credit.getAccountId()).isEqualTo(merchantAccount.getId());
        assertThat(debit.getAmount()).isEqualByComparingTo(new BigDecimal("150.00"));
        assertThat(credit.getAmount()).isEqualByComparingTo(new BigDecimal("150.00"));

        verify(processedEventRepository).save(any());
    }

    @Test
    void refundReversesTheSettlementEntries() {
        UUID eventId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();

        when(processedEventRepository.existsById(eventId)).thenReturn(false);

        service.handle(envelope(eventId, "PAYMENT_REFUNDED", paymentId, merchantId));

        ArgumentCaptor<LedgerTransaction> txCaptor = ArgumentCaptor.forClass(LedgerTransaction.class);
        verify(transactionRepository).save(txCaptor.capture());
        assertThat(txCaptor.getValue().getType()).isEqualTo(LedgerTransactionType.REFUND);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LedgerEntry>> entryCaptor = ArgumentCaptor.forClass(List.class);
        verify(entryRepository).saveAll(entryCaptor.capture());
        List<LedgerEntry> entries = entryCaptor.getValue();

        LedgerEntry debit = entries.stream().filter(e -> e.getDirection() == LedgerDirection.DEBIT).findFirst().orElseThrow();
        LedgerEntry credit = entries.stream().filter(e -> e.getDirection() == LedgerDirection.CREDIT).findFirst().orElseThrow();

        assertThat(debit.getAccountId()).isEqualTo(merchantAccount.getId());
        assertThat(credit.getAccountId()).isEqualTo(platformAccount.getId());
    }

    @Test
    void skipsAlreadyProcessedEventWithoutPostingAnything() {
        UUID eventId = UUID.randomUUID();

        when(processedEventRepository.existsById(eventId)).thenReturn(true);

        service.handle(envelope(eventId, "PAYMENT_COMPLETED", UUID.randomUUID(), UUID.randomUUID()));

        verifyNoInteractions(transactionRepository, entryRepository, accountResolver);
        verify(processedEventRepository, never()).save(any());
    }

    @Test
    void marksEventProcessedButPostsNothingForEventTypesWithNoMoneyMovement() {
        UUID eventId = UUID.randomUUID();

        when(processedEventRepository.existsById(eventId)).thenReturn(false);

        service.handle(envelope(eventId, "PAYMENT_CREATED", UUID.randomUUID(), UUID.randomUUID()));

        verifyNoInteractions(transactionRepository, entryRepository, accountResolver);
        verify(processedEventRepository).save(any());
    }

    @Test
    void rejectsEventWithMissingDataPayloadInsteadOfThrowingNpe() {
        UUID eventId = UUID.randomUUID();

        when(processedEventRepository.existsById(eventId)).thenReturn(false);

        EventEnvelope envelopeWithoutData = new EventEnvelope(eventId, "PAYMENT_COMPLETED", LocalDateTime.now(), null);

        assertThatThrownBy(() -> service.handle(envelopeWithoutData))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(eventId.toString());

        verifyNoInteractions(transactionRepository, entryRepository, accountResolver);
        verify(processedEventRepository, never()).save(any());
    }
}
