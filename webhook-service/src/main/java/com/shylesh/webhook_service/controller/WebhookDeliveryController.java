package com.shylesh.webhook_service.controller;

import com.shylesh.webhook_service.dto.WebhookDeliveryResponse;
import com.shylesh.webhook_service.exception.InvalidPaymentIdException;
import com.shylesh.webhook_service.security.CurrentMerchant;
import com.shylesh.webhook_service.service.WebhookDeliveryQueryService;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/webhooks/deliveries")
@RequiredArgsConstructor
public class WebhookDeliveryController {

    private static final String PUBLIC_PAYMENT_ID_PREFIX = "pay_";
    private static final String PUBLIC_PAYOUT_ID_PREFIX = "po_";

    private final WebhookDeliveryQueryService deliveryQueryService;

    /**
     * The calling merchant's deliveries for a payment. Accepts the public "pay_<uuid>" id (as in
     * webhook payloads) or the raw UUID. Another merchant's payment yields an empty list.
     */
    @GetMapping("/payment/{paymentId}")
    @PreAuthorize("hasAuthority(T(com.shylesh.webhook_service.security.Scopes).WEBHOOKS_MANAGE)")
    public List<WebhookDeliveryResponse> byPayment(@CurrentMerchant UUID merchantId, @PathVariable String paymentId) {
        return deliveryQueryService.findByPayment(merchantId, parseId(paymentId, PUBLIC_PAYMENT_ID_PREFIX));
    }

    /** The calling merchant's deliveries for a payout ("po_<uuid>" or the raw UUID). */
    @GetMapping("/payout/{payoutId}")
    @PreAuthorize("hasAuthority(T(com.shylesh.webhook_service.security.Scopes).WEBHOOKS_MANAGE)")
    public List<WebhookDeliveryResponse> byPayout(@CurrentMerchant UUID merchantId, @PathVariable String payoutId) {
        return deliveryQueryService.findByPayout(merchantId, parseId(payoutId, PUBLIC_PAYOUT_ID_PREFIX));
    }

    private static UUID parseId(String id, String prefix) {
        String raw = id.startsWith(prefix) ? id.substring(prefix.length()) : id;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new InvalidPaymentIdException(id);
        }
    }
}
