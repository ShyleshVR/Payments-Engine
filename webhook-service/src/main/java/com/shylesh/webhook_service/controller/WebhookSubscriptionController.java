package com.shylesh.webhook_service.controller;

import com.shylesh.webhook_service.dto.CreateWebhookSubscriptionRequest;
import com.shylesh.webhook_service.dto.WebhookSubscriptionResponse;
import com.shylesh.webhook_service.service.WebhookSubscriptionService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Unauthenticated in this phase by design: merchant identity/auth is the next roadmap item
 * and will close this gap. Until then, SSRF protection on the URL is the main safeguard.
 */
@RestController
@RequestMapping("/api/v1/webhooks/subscriptions")
@RequiredArgsConstructor
public class WebhookSubscriptionController {

    private final WebhookSubscriptionService subscriptionService;

    @PostMapping
    public ResponseEntity<WebhookSubscriptionResponse> create(@Valid @RequestBody CreateWebhookSubscriptionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(subscriptionService.create(request));
    }

    @GetMapping("/{merchantId}")
    public WebhookSubscriptionResponse get(@PathVariable UUID merchantId) {
        return subscriptionService.get(merchantId);
    }

    @DeleteMapping("/{merchantId}")
    public ResponseEntity<Void> delete(@PathVariable UUID merchantId) {
        subscriptionService.deactivate(merchantId);
        return ResponseEntity.noContent().build();
    }
}
