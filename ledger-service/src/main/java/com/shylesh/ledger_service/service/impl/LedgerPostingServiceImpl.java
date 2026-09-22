package com.shylesh.ledger_service.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shylesh.ledger_service.event.EventEnvelope;
import com.shylesh.ledger_service.event.PaymentEventData;
import com.shylesh.ledger_service.event.PaymentEventType;
import com.shylesh.ledger_service.persistence.*;
import com.shylesh.ledger_service.service.LedgerPostingService;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Only PAYMENT_COMPLETED and PAYMENT_REFUNDED move money and get ledger entries.
 * PAYMENT_CREATED and PAYMENT_FAILED (and any event type this service doesn't recognize)
 * are still recorded as processed, for idempotency, but post nothing.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LedgerPostingServiceImpl implements LedgerPostingService {

    private final ProcessedEventRepository processedEventRepository;
    private final LedgerTransactionRepository transactionRepository;
    private final LedgerEntryRepository entryRepository;
    private final LedgerAccountResolver accountResolver;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    @Override
    @Transactional
    public void handle(EventEnvelope envelope) {

        if (processedEventRepository.existsById(envelope.getEventId())) {
            log.info(
                    "Skipping already-processed event. eventId={}, eventType={}",
                    envelope.getEventId(),
                    envelope.getEventType()
            );
            return;
        }

        PaymentEventData data = objectMapper.convertValue(envelope.getData(), PaymentEventData.class);
        PaymentEventType eventType = parseEventType(envelope.getEventType());

        if (eventType == PaymentEventType.PAYMENT_COMPLETED) {
            postSettlement(envelope.getEventId(), data);
        } else if (eventType == PaymentEventType.PAYMENT_REFUNDED) {
            postRefund(envelope.getEventId(), data);
        }

        processedEventRepository.save(
                new ProcessedEvent(envelope.getEventId(), envelope.getEventType(), LocalDateTime.now())
        );

        log.info(
                "Processed event. eventId={}, eventType={}, paymentId={}",
                envelope.getEventId(),
                envelope.getEventType(),
                data.getPaymentId()
        );
    }

    private PaymentEventType parseEventType(String eventType) {
        try {
            return PaymentEventType.valueOf(eventType);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private void postSettlement(UUID eventId, PaymentEventData data) {
        LedgerAccount platform = accountResolver.resolve(LedgerAccountType.PLATFORM_CLEARING, null, data.getCurrency());
        LedgerAccount merchant = accountResolver.resolve(LedgerAccountType.MERCHANT, data.getMerchantId(), data.getCurrency());

        postBalancedTransaction(
                eventId, data.getPaymentId(), LedgerTransactionType.SETTLEMENT,
                platform.getId(), merchant.getId(), data.getAmount(), data.getCurrency()
        );
    }

    private void postRefund(UUID eventId, PaymentEventData data) {
        LedgerAccount platform = accountResolver.resolve(LedgerAccountType.PLATFORM_CLEARING, null, data.getCurrency());
        LedgerAccount merchant = accountResolver.resolve(LedgerAccountType.MERCHANT, data.getMerchantId(), data.getCurrency());

        postBalancedTransaction(
                eventId, data.getPaymentId(), LedgerTransactionType.REFUND,
                merchant.getId(), platform.getId(), data.getAmount(), data.getCurrency()
        );
    }

    private void postBalancedTransaction(
            UUID eventId, UUID paymentId, LedgerTransactionType type,
            UUID debitAccountId, UUID creditAccountId,
            BigDecimal amount, String currency
    ) {
        LocalDateTime now = LocalDateTime.now();

        LedgerTransaction transaction = transactionRepository.save(
                LedgerTransaction.builder()
                        .id(UUID.randomUUID())
                        .eventId(eventId)
                        .paymentId(paymentId)
                        .type(type)
                        .createdAt(now)
                        .build()
        );

        entryRepository.save(
                LedgerEntry.builder()
                        .id(UUID.randomUUID())
                        .transactionId(transaction.getId())
                        .accountId(debitAccountId)
                        .direction(LedgerDirection.DEBIT)
                        .amount(amount)
                        .currency(currency)
                        .createdAt(now)
                        .build()
        );

        entryRepository.save(
                LedgerEntry.builder()
                        .id(UUID.randomUUID())
                        .transactionId(transaction.getId())
                        .accountId(creditAccountId)
                        .direction(LedgerDirection.CREDIT)
                        .amount(amount)
                        .currency(currency)
                        .createdAt(now)
                        .build()
        );

        Counter.builder("ledger.transactions.posted")
                .tag("type", type.name())
                .register(meterRegistry)
                .increment();
    }
}
