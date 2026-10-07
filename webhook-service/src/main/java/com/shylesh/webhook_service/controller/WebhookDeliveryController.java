package com.shylesh.webhook_service.controller;

import com.shylesh.webhook_service.dto.WebhookDeliveryResponse;
import com.shylesh.webhook_service.exception.InvalidPaymentIdException;
import com.shylesh.webhook_service.service.WebhookDeliveryQueryService;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/webhooks/deliveries")
@RequiredArgsConstructor
public class WebhookDeliveryController {

    private static final String PUBLIC_PAYMENT_ID_PREFIX = "pay_";

    private final WebhookDeliveryQueryService deliveryQueryService;

    /** Accepts the public "pay_<uuid>" id (as in webhook payloads) or the raw UUID. */
    @GetMapping("/payment/{paymentId}")
    public List<WebhookDeliveryResponse> byPayment(@PathVariable String paymentId) {
        return deliveryQueryService.findByPayment(parsePaymentId(paymentId));
    }

    private static UUID parsePaymentId(String paymentId) {
        String raw = paymentId.startsWith(PUBLIC_PAYMENT_ID_PREFIX)
                ? paymentId.substring(PUBLIC_PAYMENT_ID_PREFIX.length())
                : paymentId;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new InvalidPaymentIdException(paymentId);
        }
    }
}
