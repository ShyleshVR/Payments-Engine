package com.shylesh.ledger_service.service;

import com.shylesh.ledger_service.dto.AccountBalanceResponse;
import com.shylesh.ledger_service.dto.LedgerTransactionResponse;
import com.shylesh.ledger_service.persistence.LedgerAccountType;

import java.util.List;
import java.util.UUID;

public interface LedgerQueryService {

    AccountBalanceResponse getBalance(LedgerAccountType ownerType, UUID ownerId, String currency);

    List<LedgerTransactionResponse> getTransactionsForPayment(UUID paymentId);
}
