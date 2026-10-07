package com.shylesh.ledger_service.service.impl;

import com.shylesh.ledger_service.persistence.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class LedgerQueryServiceImplTest {

    private LedgerAccountRepository accountRepository;
    private LedgerTransactionRepository transactionRepository;
    private LedgerEntryRepository entryRepository;
    private LedgerQueryServiceImpl service;

    private final UUID paymentId = UUID.randomUUID();
    private final UUID merchantId = UUID.randomUUID();
    private final UUID merchantAccount = UUID.randomUUID();
    private final UUID platformAccount = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        accountRepository = mock(LedgerAccountRepository.class);
        transactionRepository = mock(LedgerTransactionRepository.class);
        entryRepository = mock(LedgerEntryRepository.class);
        service = new LedgerQueryServiceImpl(accountRepository, transactionRepository, entryRepository);

        LedgerTransaction settlement = LedgerTransaction.builder().id(UUID.randomUUID()).eventId(UUID.randomUUID())
                .paymentId(paymentId).type(LedgerTransactionType.SETTLEMENT).createdAt(LocalDateTime.now()).build();
        when(transactionRepository.findByPaymentIdOrderByCreatedAtAsc(paymentId)).thenReturn(List.of(settlement));
        when(entryRepository.findByTransactionIdInOrderByCreatedAtAsc(anyList())).thenReturn(List.of(
                entry(settlement.getId(), platformAccount, LedgerDirection.DEBIT),
                entry(settlement.getId(), merchantAccount, LedgerDirection.CREDIT)));
    }

    private LedgerEntry entry(UUID transactionId, UUID accountId, LedgerDirection direction) {
        return LedgerEntry.builder().id(UUID.randomUUID()).transactionId(transactionId).accountId(accountId)
                .direction(direction).amount(BigDecimal.TEN).currency("USD").createdAt(LocalDateTime.now()).build();
    }

    private LedgerAccount account(UUID id, UUID owner) {
        return LedgerAccount.builder().id(id).ownerType(LedgerAccountType.MERCHANT).ownerId(owner).currency("USD")
                .createdAt(LocalDateTime.now()).build();
    }

    @Test
    void merchantSeesTransactionsThatTouchItsAccount() {
        when(accountRepository.findByOwnerTypeAndOwnerId(LedgerAccountType.MERCHANT, merchantId))
                .thenReturn(List.of(account(merchantAccount, merchantId)));

        assertThat(service.getTransactionsForPayment(paymentId, merchantId)).hasSize(1);
    }

    @Test
    void anotherMerchantSeesNothing() {
        UUID other = UUID.randomUUID();
        when(accountRepository.findByOwnerTypeAndOwnerId(LedgerAccountType.MERCHANT, other))
                .thenReturn(List.of(account(UUID.randomUUID(), other)));

        assertThat(service.getTransactionsForPayment(paymentId, other)).isEmpty();
    }

    @Test
    void merchantWithNoAccountsSeesNothingWithoutQueryingTransactions() {
        when(accountRepository.findByOwnerTypeAndOwnerId(LedgerAccountType.MERCHANT, merchantId)).thenReturn(List.of());

        assertThat(service.getTransactionsForPayment(paymentId, merchantId)).isEmpty();
        verifyNoInteractions(transactionRepository);
    }
}
