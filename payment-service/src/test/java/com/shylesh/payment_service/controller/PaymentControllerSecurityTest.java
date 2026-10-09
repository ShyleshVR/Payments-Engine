package com.shylesh.payment_service.controller;

import com.shylesh.payment_service.common.identifier.IdentifierServiceImpl;
import com.shylesh.payment_service.dto.CreatePaymentRequest;
import com.shylesh.payment_service.dto.PaymentResponse;
import com.shylesh.payment_service.exception.PaymentNotFoundException;
import com.shylesh.payment_service.security.JsonSecurityErrorHandler;
import com.shylesh.payment_service.security.SecurityConfig;
import com.shylesh.payment_service.service.PaymentService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** The real security configuration in front of the controller, with test JWTs. */
@WebMvcTest(PaymentController.class)
@Import({SecurityConfig.class, JsonSecurityErrorHandler.class, IdentifierServiceImpl.class})
class PaymentControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentService paymentService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private final UUID merchantId = UUID.randomUUID();
    private final UUID paymentId = UUID.randomUUID();

    private RequestPostProcessor merchant(String... scopes) {
        return jwt().jwt(token -> token.claim("merchant_id", merchantId.toString()))
                .authorities(java.util.Arrays.stream(scopes).map(s -> new SimpleGrantedAuthority("SCOPE_" + s))
                        .toArray(SimpleGrantedAuthority[]::new));
    }

    private RequestPostProcessor operator() {
        return jwt().authorities(new SimpleGrantedAuthority("SCOPE_payments:operate"));
    }

    private PaymentResponse response() {
        return PaymentResponse.builder().paymentId("pay_" + paymentId).amount(new BigDecimal("10.00")).currency("USD")
                .status("CREATED").createdAt(LocalDateTime.now()).build();
    }

    private static final String BODY = "{\"amount\":10.00,\"currency\":\"USD\",\"paymentMethod\":\"pm_card_visa\"}";

    @Test
    void noTokenIs401WithBearerChallengeAndJsonBody() throws Exception {
        mockMvc.perform(get("/api/v1/payments/pay_" + paymentId))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Bearer")))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").exists());
        verifyNoInteractions(paymentService);
    }

    @Test
    void healthStaysPublic() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(result -> {
            int status = result.getResponse().getStatus();
            if (status == 401 || status == 403) {
                throw new AssertionError("actuator/health must not require a token, got " + status);
            }
        });
    }

    @Test
    void createUsesTheTokensMerchantAndIgnoresAnyMerchantInTheBody() throws Exception {
        when(paymentService.createPayment(eq(merchantId), eq("k1"), any(CreatePaymentRequest.class))).thenReturn(response());

        mockMvc.perform(post("/api/v1/payments").with(merchant("payments:write"))
                        .header("Idempotency-Key", "k1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":10.00,\"currency\":\"USD\",\"paymentMethod\":\"pm_card_visa\",\"merchantId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isCreated());

        verify(paymentService).createPayment(eq(merchantId), eq("k1"), any(CreatePaymentRequest.class));
    }

    @Test
    void createWithoutWriteScopeIs403() throws Exception {
        mockMvc.perform(post("/api/v1/payments").with(merchant("payments:read"))
                        .header("Idempotency-Key", "k1").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
        verifyNoInteractions(paymentService);
    }

    @Test
    void createWithoutAPaymentMethodIs400() throws Exception {
        mockMvc.perform(post("/api/v1/payments").with(merchant("payments:write"))
                        .header("Idempotency-Key", "k1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":10.00,\"currency\":\"USD\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(paymentService);
    }

    @Test
    void merchantCannotInspectOrRetrySagas() throws Exception {
        mockMvc.perform(get("/api/v1/payments/pay_" + paymentId + "/saga").with(merchant("payments:write", "payments:read")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/payments/pay_" + paymentId + "/saga/retry").with(merchant("payments:write", "payments:read")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(paymentService);
    }

    @Test
    void operatorCanInspectAndRetrySagas() throws Exception {
        when(paymentService.getSagas(paymentId)).thenReturn(List.of());
        when(paymentService.retrySaga(paymentId)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/payments/pay_" + paymentId + "/saga").with(operator()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/payments/pay_" + paymentId + "/saga/retry").with(operator()))
                .andExpect(status().isOk());
    }

    @Test
    void operatorCannotCaptureOrRefundForAMerchant() throws Exception {
        for (String action : new String[]{"capture", "cancel", "refund"}) {
            mockMvc.perform(post("/api/v1/payments/pay_" + paymentId + "/" + action).with(operator()))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(paymentService);
    }

    @Test
    void anotherMerchantsPaymentIs404() throws Exception {
        when(paymentService.getPayment(merchantId, paymentId)).thenThrow(new PaymentNotFoundException(paymentId));

        mockMvc.perform(get("/api/v1/payments/pay_" + paymentId).with(merchant("payments:read")))
                .andExpect(status().isNotFound());
    }

    @Test
    void merchantScopeWithoutAMerchantClaimIs403() throws Exception {
        mockMvc.perform(get("/api/v1/payments/pay_" + paymentId)
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_payments:read"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("This endpoint requires a merchant token"));
        verifyNoInteractions(paymentService);
    }

    @Test
    void refundCaptureAndCancelAreAcceptedForTheTokensMerchant() throws Exception {
        when(paymentService.refundPayment(merchantId, paymentId)).thenReturn(response());
        when(paymentService.capturePayment(merchantId, paymentId)).thenReturn(response());
        when(paymentService.cancelPayment(merchantId, paymentId)).thenReturn(response());

        for (String action : new String[]{"refund", "capture", "cancel"}) {
            mockMvc.perform(post("/api/v1/payments/pay_" + paymentId + "/" + action).with(merchant("payments:write")))
                    .andExpect(status().isAccepted());
        }
        verify(paymentService).refundPayment(merchantId, paymentId);
        verify(paymentService).capturePayment(merchantId, paymentId);
        verify(paymentService).cancelPayment(merchantId, paymentId);
    }
}
