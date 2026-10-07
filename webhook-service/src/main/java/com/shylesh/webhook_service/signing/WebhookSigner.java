package com.shylesh.webhook_service.signing;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Merchant-facing signing contract:
 *   X-Webhook-Timestamp: unix seconds when the request was signed
 *   X-Webhook-Signature: sha256=hex(HMAC-SHA256(secret, "<timestamp>.<raw body>"))
 * Merchants recompute the HMAC over the exact bytes received, compare in constant time, and
 * reject timestamps older than a few minutes. Covering the timestamp means a captured request
 * can't be replayed later with a fresh timestamp.
 */
@Component
public class WebhookSigner {

    public static final String SIGNATURE_HEADER = "X-Webhook-Signature";
    public static final String TIMESTAMP_HEADER = "X-Webhook-Timestamp";

    private static final String ALGORITHM = "HmacSHA256";
    private static final String SIGNATURE_PREFIX = "sha256=";
    private static final String SECRET_PREFIX = "whsec_";
    private static final int SECRET_BYTES = 32;

    private final SecureRandom secureRandom = new SecureRandom();

    public String sign(String secret, long timestamp, byte[] rawBody) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
            return SIGNATURE_PREFIX + HexFormat.of().formatHex(mac.doFinal(rawBody));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to compute webhook signature", e);
        }
    }

    public String newSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        secureRandom.nextBytes(bytes);
        return SECRET_PREFIX + HexFormat.of().formatHex(bytes);
    }
}
