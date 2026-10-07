package com.shylesh.webhook_service.controller;

import com.shylesh.webhook_service.dto.CreateWebhookSubscriptionRequest;
import com.shylesh.webhook_service.dto.WebhookSubscriptionResponse;
import com.shylesh.webhook_service.security.CurrentMerchant;
import com.shylesh.webhook_service.service.WebhookSubscriptionService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** The calling merchant's own webhook subscription; the merchant always comes from the token. */
@RestController
@RequestMapping("/api/v1/webhooks/subscriptions")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority(T(com.shylesh.webhook_service.security.Scopes).WEBHOOKS_MANAGE)")
public class WebhookSubscriptionController {

    private final WebhookSubscriptionService subscriptionService;

    @PostMapping
    public ResponseEntity<WebhookSubscriptionResponse> create(@CurrentMerchant UUID merchantId,
                                                              @Valid @RequestBody CreateWebhookSubscriptionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(subscriptionService.create(merchantId, request));
    }

    @GetMapping
    public WebhookSubscriptionResponse get(@CurrentMerchant UUID merchantId) {
        return subscriptionService.get(merchantId);
    }

    @DeleteMapping
    public ResponseEntity<Void> delete(@CurrentMerchant UUID merchantId) {
        subscriptionService.deactivate(merchantId);
        return ResponseEntity.noContent().build();
    }
}
