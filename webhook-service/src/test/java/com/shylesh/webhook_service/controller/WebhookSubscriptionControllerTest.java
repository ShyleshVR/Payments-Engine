package com.shylesh.webhook_service.controller;

import com.shylesh.webhook_service.dto.CreateWebhookSubscriptionRequest;
import com.shylesh.webhook_service.dto.WebhookSubscriptionResponse;
import com.shylesh.webhook_service.exception.GlobalExceptionHandler;
import com.shylesh.webhook_service.exception.SubscriptionAlreadyExistsException;
import com.shylesh.webhook_service.exception.SubscriptionNotFoundException;
import com.shylesh.webhook_service.service.WebhookSubscriptionService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Standalone MockMvc: exercises routing, validation and error mapping without a Spring context. */
class WebhookSubscriptionControllerTest {

    private WebhookSubscriptionService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(WebhookSubscriptionService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new WebhookSubscriptionController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void createReturns201WithSecret() throws Exception {
        UUID merchantId = UUID.randomUUID();
        when(service.create(any(CreateWebhookSubscriptionRequest.class))).thenReturn(
                new WebhookSubscriptionResponse(UUID.randomUUID(), merchantId, "https://m.example.com", true, null, "whsec_abc")
        );

        mockMvc.perform(post("/api/v1/webhooks/subscriptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"merchantId\":\"" + merchantId + "\",\"url\":\"https://m.example.com\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.secret").value("whsec_abc"));
    }

    @Test
    void createReturns400WhenMerchantIdMissing() throws Exception {
        mockMvc.perform(post("/api/v1/webhooks/subscriptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://m.example.com\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void createReturns409WhenAlreadySubscribed() throws Exception {
        UUID merchantId = UUID.randomUUID();
        when(service.create(any())).thenThrow(new SubscriptionAlreadyExistsException(merchantId));

        mockMvc.perform(post("/api/v1/webhooks/subscriptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"merchantId\":\"" + merchantId + "\",\"url\":\"https://m.example.com\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void getReturns404WhenNoActiveSubscription() throws Exception {
        UUID merchantId = UUID.randomUUID();
        when(service.getActive(merchantId)).thenThrow(new SubscriptionNotFoundException(merchantId));

        mockMvc.perform(get("/api/v1/webhooks/subscriptions/{merchantId}", merchantId))
                .andExpect(status().isNotFound());
    }

    @Test
    void getReturns400ForMalformedMerchantId() throws Exception {
        mockMvc.perform(get("/api/v1/webhooks/subscriptions/{merchantId}", "not-a-uuid"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deleteReturns204() throws Exception {
        UUID merchantId = UUID.randomUUID();

        mockMvc.perform(delete("/api/v1/webhooks/subscriptions/{merchantId}", merchantId))
                .andExpect(status().isNoContent());

        verify(service).deactivate(merchantId);
    }
}
