package com.shylesh.webhook_service.security;

import com.shylesh.webhook_service.TestProperties;
import com.shylesh.webhook_service.exception.InvalidWebhookUrlException;
import com.shylesh.webhook_service.security.WebhookTargetValidator.TargetCheck;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebhookTargetValidatorTest {

    private final WebhookTargetValidator validator = new WebhookTargetValidator(TestProperties.defaults());

    @ParameterizedTest
    @ValueSource(strings = {
            "https://127.0.0.1/hook",          // loopback
            "https://10.1.2.3/hook",           // private
            "https://172.16.0.1/hook",         // private
            "https://192.168.1.10/hook",       // private
            "https://169.254.169.254/latest",  // link-local: cloud metadata endpoint
            "https://100.64.0.1/hook",         // carrier-grade NAT
            "https://0.0.0.0/hook",
            "https://[::1]/hook",              // IPv6 loopback
            "https://[fd00::1]/hook",          // IPv6 unique local
            "https://[fe80::1]/hook"           // IPv6 link-local
    })
    void blocksInternalTargets(String url) {
        assertThat(validator.checkTarget(URI.create(url))).isEqualTo(TargetCheck.BLOCKED);
        assertThatThrownBy(() -> validator.validateForSubscription(url))
                .isInstanceOf(InvalidWebhookUrlException.class)
                .hasMessageContaining("private");
    }

    @Test
    void allowsPublicAddresses() {
        assertThat(validator.checkTarget(URI.create("https://93.184.216.34/hook"))).isEqualTo(TargetCheck.ALLOWED);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ftp://93.184.216.34/hook",
            "http://93.184.216.34/hook",            // https required by default
            "https://user:pass@93.184.216.34/hook",
            "https://93.184.216.34/hook#frag",
            "not a url",
            "https:///no-host"
    })
    void rejectsMalformedOrUnsafeUrls(String url) {
        assertThatThrownBy(() -> validator.parse(url)).isInstanceOf(InvalidWebhookUrlException.class);
    }

    @Test
    void developmentFlagsAllowPlainHttpToLocalhost() {
        WebhookTargetValidator dev = new WebhookTargetValidator(TestProperties.with(false, true));

        URI uri = dev.validateForSubscription("http://localhost:9999/hook");

        assertThat(uri.getHost()).isEqualTo("localhost");
    }
}
