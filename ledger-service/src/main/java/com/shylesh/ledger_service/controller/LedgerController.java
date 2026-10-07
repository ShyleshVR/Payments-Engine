package com.shylesh.ledger_service.controller;

import com.shylesh.ledger_service.dto.AccountBalanceResponse;
import com.shylesh.ledger_service.dto.LedgerTransactionResponse;
import com.shylesh.ledger_service.persistence.LedgerAccountType;
import com.shylesh.ledger_service.security.CurrentMerchant;
import com.shylesh.ledger_service.security.CurrentMerchantArgumentResolver;
import com.shylesh.ledger_service.security.Scopes;
import com.shylesh.ledger_service.service.LedgerQueryService;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ledger")
@RequiredArgsConstructor
public class LedgerController {

    private final LedgerQueryService ledgerQueryService;

    /** The calling merchant's own balance. */
    @GetMapping("/balance")
    @PreAuthorize("hasAuthority(T(com.shylesh.ledger_service.security.Scopes).LEDGER_READ)")
    public AccountBalanceResponse getOwnBalance(@CurrentMerchant UUID merchantId, @RequestParam String currency) {
        return ledgerQueryService.getBalance(LedgerAccountType.MERCHANT, merchantId, currency);
    }

    /** Any merchant's balance: operators only. */
    @GetMapping("/merchants/{merchantId}/balance")
    @PreAuthorize("hasAuthority(T(com.shylesh.ledger_service.security.Scopes).LEDGER_ADMIN)")
    public AccountBalanceResponse getMerchantBalance(@PathVariable UUID merchantId, @RequestParam String currency) {
        return ledgerQueryService.getBalance(LedgerAccountType.MERCHANT, merchantId, currency);
    }

    @GetMapping("/platform-clearing/balance")
    @PreAuthorize("hasAuthority(T(com.shylesh.ledger_service.security.Scopes).LEDGER_ADMIN)")
    public AccountBalanceResponse getPlatformClearingBalance(@RequestParam String currency) {
        return ledgerQueryService.getBalance(LedgerAccountType.PLATFORM_CLEARING, null, currency);
    }

    /**
     * Operators see every transaction of the payment; a merchant sees only transactions that
     * touch its own account (an empty list for anyone else's payment, so ids can't be probed).
     */
    @GetMapping("/payments/{paymentId}/transactions")
    @PreAuthorize("hasAnyAuthority(T(com.shylesh.ledger_service.security.Scopes).LEDGER_READ, T(com.shylesh.ledger_service.security.Scopes).LEDGER_ADMIN)")
    public List<LedgerTransactionResponse> getTransactionsForPayment(@PathVariable UUID paymentId,
                                                                     JwtAuthenticationToken authentication) {
        boolean admin = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(Scopes.LEDGER_ADMIN::equals);

        if (admin) {
            return ledgerQueryService.getTransactionsForPayment(paymentId);
        }
        return ledgerQueryService.getTransactionsForPayment(paymentId, CurrentMerchantArgumentResolver.merchantId(authentication));
    }
}
