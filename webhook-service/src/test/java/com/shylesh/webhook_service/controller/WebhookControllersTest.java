package com.shylesh.webhook_service.controller;

import com.shylesh.webhook_service.dto.CreateWebhookSubscriptionRequest;
import com.shylesh.webhook_service.dto.WebhookSubscriptionResponse;
import com.shylesh.webhook_service.exception.InvalidWebhookUrlException;
import com.shylesh.webhook_service.exception.SubscriptionAlreadyExistsException;
import com.shylesh.webhook_service.exception.SubscriptionNotFoundException;
import com.shylesh.webhook_service.service.WebhookDeliveryQueryService;
import com.shylesh.webhook_service.service.WebhookSubscriptionService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest({WebhookSubscriptionController.class, WebhookDeliveryController.class})
class WebhookControllersTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WebhookSubscriptionService subscriptionService;

    @MockitoBean
    private WebhookDeliveryQueryService deliveryQueryService;

    private final UUID merchantId = UUID.randomUUID();

    private String body(String url) {
        return "{\"merchantId\":\"" + merchantId + "\",\"url\":\"" + url + "\"}";
    }

    @Test
    void createReturns201WithTheSecret() throws Exception {
        when(subscriptionService.create(any(CreateWebhookSubscriptionRequest.class))).thenReturn(
                new WebhookSubscriptionResponse(UUID.randomUUID(), merchantId, "https://m.example/h", true, LocalDateTime.now(), "whsec_abc"));

        mockMvc.perform(post("/api/v1/webhooks/subscriptions").contentType(MediaType.APPLICATION_JSON).content(body("https://m.example/h")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.secret").value("whsec_abc"));
    }

    @Test
    void getNeverIncludesASecretField() throws Exception {
        when(subscriptionService.get(merchantId)).thenReturn(
                new WebhookSubscriptionResponse(UUID.randomUUID(), merchantId, "https://m.example/h", true, LocalDateTime.now(), null));

        mockMvc.perform(get("/api/v1/webhooks/subscriptions/" + merchantId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.secret").doesNotExist());
    }

    @Test
    void missingFieldsAre400InTheStandardErrorShape() throws Exception {
        mockMvc.perform(post("/api/v1/webhooks/subscriptions").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("merchantId")))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void unsafeUrlIs400() throws Exception {
        when(subscriptionService.create(any())).thenThrow(new InvalidWebhookUrlException("Webhook URL must not point to a private address"));

        mockMvc.perform(post("/api/v1/webhooks/subscriptions").contentType(MediaType.APPLICATION_JSON).content(body("https://10.0.0.1/h")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void duplicateIs409AndMissingIs404() throws Exception {
        when(subscriptionService.create(any())).thenThrow(new SubscriptionAlreadyExistsException(merchantId));
        doThrow(new SubscriptionNotFoundException(merchantId)).when(subscriptionService).deactivate(merchantId);

        mockMvc.perform(post("/api/v1/webhooks/subscriptions").contentType(MediaType.APPLICATION_JSON).content(body("https://m.example/h")))
                .andExpect(status().isConflict());
        mockMvc.perform(delete("/api/v1/webhooks/subscriptions/" + merchantId))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteIs204() throws Exception {
        mockMvc.perform(delete("/api/v1/webhooks/subscriptions/" + merchantId))
                .andExpect(status().isNoContent());
    }

    @Test
    void deliveryAuditAcceptsPublicAndRawPaymentIds() throws Exception {
        UUID paymentId = UUID.randomUUID();
        when(deliveryQueryService.findByPayment(paymentId)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/webhooks/deliveries/payment/pay_" + paymentId)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/webhooks/deliveries/payment/" + paymentId)).andExpect(status().isOk());
        verify(deliveryQueryService, times(2)).findByPayment(paymentId);

        mockMvc.perform(get("/api/v1/webhooks/deliveries/payment/garbage"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid payment id: garbage"));
    }
}
