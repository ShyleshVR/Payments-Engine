package com.shylesh.webhook_service.controller;

import com.shylesh.webhook_service.dto.WebhookDeliveryResponse;
import com.shylesh.webhook_service.service.WebhookDeliveryQueryService;

import lombok.RequiredArgsConstructor;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Audit view: every webhook delivery (and its attempts) for a payment. */
@RestController
@RequestMapping("/api/v1/webhooks/deliveries")
@RequiredArgsConstructor
public class WebhookDeliveryController {

    private final WebhookDeliveryQueryService deliveryQueryService;

    @GetMapping("/payment/{paymentId}")
    public ResponseEntity<List<WebhookDeliveryResponse>> byPayment(@PathVariable UUID paymentId) {

        return ResponseEntity.ok(deliveryQueryService.findByPaymentId(paymentId));
    }
}
