package com.shylesh.ledger_service.service;

import com.shylesh.ledger_service.dto.AccountBalanceResponse;
import com.shylesh.ledger_service.dto.LedgerTransactionResponse;
import com.shylesh.ledger_service.persistence.LedgerAccountType;
import com.shylesh.ledger_service.persistence.LedgerEntryRepository.PayableBalance;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface LedgerQueryService {

    AccountBalanceResponse getBalance(LedgerAccountType ownerType, UUID ownerId, String currency);

    List<LedgerTransactionResponse> getTransactionsForPayment(UUID paymentId);

    /** Only the payment's transactions that touch one of this merchant's accounts. */
    List<LedgerTransactionResponse> getTransactionsForPayment(UUID paymentId, UUID merchantId);

    List<LedgerTransactionResponse> getTransactionsForPayout(UUID payoutId);

    /** The merchant's balance minus settlements at or after the cutoff (never negative). */
    BigDecimal getPayable(UUID merchantId, String currency, LocalDateTime cutoff);

    /** Every merchant balance with at least the minimum payable (the daily payout batch). */
    List<PayableBalance> getPayableBalances(LocalDateTime cutoff, BigDecimal minimum);
}
