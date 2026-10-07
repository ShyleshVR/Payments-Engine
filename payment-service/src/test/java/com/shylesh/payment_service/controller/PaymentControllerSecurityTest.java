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

    private static final String BODY = "{\"amount\":10.00,\"currency\":\"USD\"}";

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
                        .content("{\"amount\":10.00,\"currency\":\"USD\",\"merchantId\":\"" + UUID.randomUUID() + "\"}"))
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
    void merchantCannotDriveProcessingOutcomes() throws Exception {
        for (String action : new String[]{"process", "complete", "fail"}) {
            mockMvc.perform(post("/api/v1/payments/pay_" + paymentId + "/" + action)
                            .with(merchant("payments:write", "payments:read")))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(paymentService);
    }

    @Test
    void operatorCanDriveProcessingOutcomes() throws Exception {
        when(paymentService.completePayment(paymentId)).thenReturn(response());

        mockMvc.perform(post("/api/v1/payments/pay_" + paymentId + "/complete").with(operator()))
                .andExpect(status().isOk());
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
    void refundIsScopedToTheTokensMerchant() throws Exception {
        when(paymentService.refundPayment(merchantId, paymentId)).thenReturn(response());

        mockMvc.perform(post("/api/v1/payments/pay_" + paymentId + "/refund").with(merchant("payments:write")))
                .andExpect(status().isOk());
        verify(paymentService).refundPayment(merchantId, paymentId);
    }
}
