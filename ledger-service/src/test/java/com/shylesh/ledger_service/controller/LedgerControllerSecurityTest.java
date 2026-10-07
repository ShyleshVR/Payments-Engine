package com.shylesh.ledger_service.controller;

import com.shylesh.ledger_service.dto.AccountBalanceResponse;
import com.shylesh.ledger_service.persistence.LedgerAccountType;
import com.shylesh.ledger_service.security.JsonSecurityErrorHandler;
import com.shylesh.ledger_service.security.SecurityConfig;
import com.shylesh.ledger_service.service.LedgerQueryService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(LedgerController.class)
@Import({SecurityConfig.class, JsonSecurityErrorHandler.class})
class LedgerControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LedgerQueryService ledgerQueryService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private final UUID merchantId = UUID.randomUUID();

    private RequestPostProcessor merchant() {
        return jwt().jwt(token -> token.claim("merchant_id", merchantId.toString()))
                .authorities(new SimpleGrantedAuthority("SCOPE_ledger:read"));
    }

    private RequestPostProcessor operator() {
        return jwt().authorities(new SimpleGrantedAuthority("SCOPE_ledger:admin"));
    }

    private AccountBalanceResponse balance(LedgerAccountType type, UUID owner) {
        return AccountBalanceResponse.builder().ownerType(type).ownerId(owner).currency("USD").balance(BigDecimal.TEN).build();
    }

    @Test
    void merchantReadsItsOwnBalanceFromTheToken() throws Exception {
        when(ledgerQueryService.getBalance(LedgerAccountType.MERCHANT, merchantId, "USD"))
                .thenReturn(balance(LedgerAccountType.MERCHANT, merchantId));

        mockMvc.perform(get("/api/v1/ledger/balance").param("currency", "USD").with(merchant()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerId").value(merchantId.toString()));
    }

    @Test
    void merchantCannotReadAnotherMerchantsBalanceOrPlatformClearing() throws Exception {
        mockMvc.perform(get("/api/v1/ledger/merchants/" + UUID.randomUUID() + "/balance").param("currency", "USD").with(merchant()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/ledger/platform-clearing/balance").param("currency", "USD").with(merchant()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(ledgerQueryService);
    }

    @Test
    void operatorReadsAnyBalance() throws Exception {
        UUID other = UUID.randomUUID();
        when(ledgerQueryService.getBalance(LedgerAccountType.MERCHANT, other, "USD")).thenReturn(balance(LedgerAccountType.MERCHANT, other));
        when(ledgerQueryService.getBalance(LedgerAccountType.PLATFORM_CLEARING, null, "USD")).thenReturn(balance(LedgerAccountType.PLATFORM_CLEARING, null));

        mockMvc.perform(get("/api/v1/ledger/merchants/" + other + "/balance").param("currency", "USD").with(operator()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/ledger/platform-clearing/balance").param("currency", "USD").with(operator()))
                .andExpect(status().isOk());
    }

    @Test
    void transactionsAreMerchantScopedForMerchantsAndUnscopedForOperators() throws Exception {
        UUID paymentId = UUID.randomUUID();
        when(ledgerQueryService.getTransactionsForPayment(any(UUID.class), any(UUID.class))).thenReturn(List.of());
        when(ledgerQueryService.getTransactionsForPayment(any(UUID.class))).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/ledger/payments/" + paymentId + "/transactions").with(merchant()))
                .andExpect(status().isOk());
        verify(ledgerQueryService).getTransactionsForPayment(paymentId, merchantId);

        mockMvc.perform(get("/api/v1/ledger/payments/" + paymentId + "/transactions").with(operator()))
                .andExpect(status().isOk());
        verify(ledgerQueryService).getTransactionsForPayment(paymentId);
    }

    @Test
    void noTokenIs401AndOperatorOnOwnBalanceIs403() throws Exception {
        mockMvc.perform(get("/api/v1/ledger/balance").param("currency", "USD"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));

        mockMvc.perform(get("/api/v1/ledger/balance").param("currency", "USD")
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_ledger:read"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("This endpoint requires a merchant token"));
        verify(ledgerQueryService, never()).getBalance(any(), eq(null), any());
    }
}
