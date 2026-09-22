package com.shylesh.ledger_service.controller;

import com.shylesh.ledger_service.dto.AccountBalanceResponse;
import com.shylesh.ledger_service.dto.LedgerTransactionResponse;
import com.shylesh.ledger_service.persistence.LedgerAccountType;
import com.shylesh.ledger_service.service.LedgerQueryService;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ledger")
@RequiredArgsConstructor
public class LedgerController {

    private final LedgerQueryService ledgerQueryService;

    @GetMapping("/merchants/{merchantId}/balance")
    public AccountBalanceResponse getMerchantBalance(
            @PathVariable UUID merchantId,
            @RequestParam String currency
    ) {
        return ledgerQueryService.getBalance(LedgerAccountType.MERCHANT, merchantId, currency);
    }

    @GetMapping("/platform-clearing/balance")
    public AccountBalanceResponse getPlatformClearingBalance(
            @RequestParam String currency
    ) {
        return ledgerQueryService.getBalance(LedgerAccountType.PLATFORM_CLEARING, null, currency);
    }

    @GetMapping("/payments/{paymentId}/transactions")
    public List<LedgerTransactionResponse> getTransactionsForPayment(
            @PathVariable UUID paymentId
    ) {
        return ledgerQueryService.getTransactionsForPayment(paymentId);
    }
}
