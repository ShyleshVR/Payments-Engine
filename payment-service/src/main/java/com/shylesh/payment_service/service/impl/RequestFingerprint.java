package com.shylesh.payment_service.service.impl;

import com.shylesh.payment_service.dto.CreatePaymentRequest;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 over a canonical form of the create request, so a replayed Idempotency-Key can be
 * checked against the body it was first used with. Amount is normalised (10, 10.0 and 10.00
 * are the same request) and every field is length-prefixed so no value can be crafted to
 * collide with a different split of fields.
 */
@Component
public class RequestFingerprint {

    public String of(CreatePaymentRequest request) {
        StringBuilder canonical = new StringBuilder();
        append(canonical, request.getAmount() == null ? null : request.getAmount().stripTrailingZeros().toPlainString());
        append(canonical, request.getCurrency());
        append(canonical, request.getMerchantId() == null ? null : request.getMerchantId().toString());
        append(canonical, request.getCustomerId() == null ? null : request.getCustomerId().toString());
        append(canonical, request.getDescription());

        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private void append(StringBuilder canonical, String value) {
        if (value == null) {
            canonical.append("-1:");
        } else {
            canonical.append(value.length()).append(':').append(value);
        }
    }
}
