package com.shylesh.webhook_service.signing;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

/**
 * Produces the X-Webhook-Signature header value: sha256=<hex HMAC-SHA256(secret, rawBody)>.
 * Merchants verify by recomputing the HMAC over the exact bytes they received — so the
 * signature is always computed over the same byte[] that is put on the wire.
 */
@Component
public class WebhookSigner {

    public static final String SIGNATURE_HEADER = "X-Webhook-Signature";

    private static final String ALGORITHM = "HmacSHA256";
    private static final String PREFIX = "sha256=";

    public String sign(String secret, byte[] rawBody) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return PREFIX + HexFormat.of().formatHex(mac.doFinal(rawBody));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to compute webhook signature", e);
        }
    }
}
