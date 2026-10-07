package com.shylesh.webhook_service.signing;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookSignerTest {

    private final WebhookSigner signer = new WebhookSigner();

    @Test
    void matchesAnIndependentlyComputedSignature() {
        // python: hmac.new(b"whsec_test", b"1700000000." + b'{"a":1}', hashlib.sha256).hexdigest()
        String signature = signer.sign("whsec_test", 1700000000L, "{\"a\":1}".getBytes(StandardCharsets.UTF_8));

        assertThat(signature).isEqualTo("sha256=38877139021993b830af32feea6e18a8da83eb2f6e49ee50bd9e4cf4ca4d3789");
    }

    @Test
    void timestampIsPartOfTheSignedContent() {
        byte[] body = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);

        assertThat(signer.sign("whsec_test", 1700000000L, body))
                .isNotEqualTo(signer.sign("whsec_test", 1700000001L, body));
    }

    @Test
    void newSecretsAreRandomAndPrefixed() {
        String first = signer.newSecret();
        String second = signer.newSecret();

        assertThat(first).startsWith("whsec_").hasSize("whsec_".length() + 64);
        assertThat(first).isNotEqualTo(second);
    }
}
