package com.shylesh.webhook_service.controller;

import com.shylesh.webhook_service.dto.CreateWebhookSubscriptionRequest;
import com.shylesh.webhook_service.dto.WebhookSubscriptionResponse;
import com.shylesh.webhook_service.exception.InvalidWebhookUrlException;
import com.shylesh.webhook_service.exception.SubscriptionAlreadyExistsException;
import com.shylesh.webhook_service.exception.SubscriptionNotFoundException;
import com.shylesh.webhook_service.security.JsonSecurityErrorHandler;
import com.shylesh.webhook_service.security.SecurityConfig;
import com.shylesh.webhook_service.service.WebhookDeliveryQueryService;
import com.shylesh.webhook_service.service.WebhookSubscriptionService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Subscription and delivery-audit API behind the real security configuration, with test JWTs. */
@WebMvcTest({WebhookSubscriptionController.class, WebhookDeliveryController.class})
@Import({SecurityConfig.class, JsonSecurityErrorHandler.class})
class WebhookControllersTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WebhookSubscriptionService subscriptionService;

    @MockitoBean
    private WebhookDeliveryQueryService deliveryQueryService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private final UUID merchantId = UUID.randomUUID();

    private RequestPostProcessor merchant() {
        return jwt().jwt(token -> token.claim("merchant_id", merchantId.toString()))
                .authorities(new SimpleGrantedAuthority("SCOPE_webhooks:manage"));
    }

    private WebhookSubscriptionResponse subscription(String secret) {
        return new WebhookSubscriptionResponse(UUID.randomUUID(), merchantId, "https://m.example/h", true, LocalDateTime.now(), secret);
    }

    @Test
    void createUsesTheTokensMerchantAndReturnsTheSecretOnce() throws Exception {
        when(subscriptionService.create(eq(merchantId), any(CreateWebhookSubscriptionRequest.class))).thenReturn(subscription("whsec_abc"));

        mockMvc.perform(post("/api/v1/webhooks/subscriptions").with(merchant())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://m.example/h\",\"merchantId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.secret").value("whsec_abc"));

        verify(subscriptionService).create(eq(merchantId), any(CreateWebhookSubscriptionRequest.class));
    }

    @Test
    void getAndDeleteActOnTheTokensMerchantWithoutAPathId() throws Exception {
        when(subscriptionService.get(merchantId)).thenReturn(subscription(null));

        mockMvc.perform(get("/api/v1/webhooks/subscriptions").with(merchant()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.secret").doesNotExist());
        mockMvc.perform(delete("/api/v1/webhooks/subscriptions").with(merchant()))
                .andExpect(status().isNoContent());
        verify(subscriptionService).deactivate(merchantId);
    }

    @Test
    void noTokenIs401AndWrongScopeIs403() throws Exception {
        mockMvc.perform(get("/api/v1/webhooks/subscriptions"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));

        mockMvc.perform(get("/api/v1/webhooks/subscriptions")
                        .with(jwt().jwt(token -> token.claim("merchant_id", merchantId.toString()))
                                .authorities(new SimpleGrantedAuthority("SCOPE_payments:read"))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(subscriptionService);
    }

    @Test
    void operatorTokenWithoutMerchantIs403() throws Exception {
        mockMvc.perform(get("/api/v1/webhooks/subscriptions")
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_webhooks:manage"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("This endpoint requires a merchant token"));
    }

    @Test
    void validationErrorsAreStill400InTheStandardShape() throws Exception {
        mockMvc.perform(post("/api/v1/webhooks/subscriptions").with(merchant())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("url")));
    }

    @Test
    void unsafeUrlIs400DuplicateIs409MissingIs404() throws Exception {
        when(subscriptionService.create(eq(merchantId), any()))
                .thenThrow(new InvalidWebhookUrlException("Webhook URL must not point to a private address"))
                .thenThrow(new SubscriptionAlreadyExistsException(merchantId));
        doThrow(new SubscriptionNotFoundException(merchantId)).when(subscriptionService).deactivate(merchantId);

        mockMvc.perform(post("/api/v1/webhooks/subscriptions").with(merchant())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"https://10.0.0.1/h\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/webhooks/subscriptions").with(merchant())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"https://m.example/h\"}"))
                .andExpect(status().isConflict());
        mockMvc.perform(delete("/api/v1/webhooks/subscriptions").with(merchant()))
                .andExpect(status().isNotFound());
    }

    @Test
    void deliveryAuditIsScopedToTheTokensMerchantAndAcceptsBothIdForms() throws Exception {
        UUID paymentId = UUID.randomUUID();
        when(deliveryQueryService.findByPayment(merchantId, paymentId)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/webhooks/deliveries/payment/pay_" + paymentId).with(merchant())).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/webhooks/deliveries/payment/" + paymentId).with(merchant())).andExpect(status().isOk());
        verify(deliveryQueryService, times(2)).findByPayment(merchantId, paymentId);

        mockMvc.perform(get("/api/v1/webhooks/deliveries/payment/garbage").with(merchant()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid payment id: garbage"));
    }
}
