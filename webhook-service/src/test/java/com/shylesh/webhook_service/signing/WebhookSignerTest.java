package com.shylesh.webhook_service.signing;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookSignerTest {

    private final WebhookSigner signer = new WebhookSigner();

    @Test
    void producesPrefixedHexHmacSha256MatchingKnownVector() {
        // Well-known HMAC-SHA256 test vector.
        String signature = signer.sign(
                "key",
                "The quick brown fox jumps over the lazy dog".getBytes(StandardCharsets.UTF_8)
        );

        assertThat(signature)
                .isEqualTo("sha256=f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8");
    }

    @Test
    void differentSecretsProduceDifferentSignatures() {
        byte[] body = "{\"payloadVersion\":\"1\"}".getBytes(StandardCharsets.UTF_8);

        assertThat(signer.sign("secret-a", body)).isNotEqualTo(signer.sign("secret-b", body));
    }
}
