package com.shylesh.api_gateway.filter;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitKeyResolverTest {

    private final RateLimitKeyResolver resolver = new RateLimitKeyResolver();

    private MockServerWebExchange exchange(JwtAuthenticationToken principal) {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/payments/x")
                .remoteAddress(new InetSocketAddress("203.0.113.7", 50000))
                .header("X-Forwarded-For", "1.2.3.4")
                .build();
        MockServerWebExchange.Builder builder = MockServerWebExchange.builder(request);
        if (principal != null) {
            builder.principal(principal);
        }
        return builder.build();
    }

    private JwtAuthenticationToken token(String subject, String merchantId) {
        Jwt.Builder jwt = Jwt.withTokenValue("t").header("alg", "RS256").subject(subject)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        if (merchantId != null) {
            jwt.claim("merchant_id", merchantId);
        }
        return new JwtAuthenticationToken(jwt.build());
    }

    @Test
    void merchantTokensCountAgainstTheMerchant() {
        String merchantId = UUID.randomUUID().toString();

        assertThat(resolver.resolve(exchange(token("mch_abc", merchantId))).block()).isEqualTo("merchant:" + merchantId);
        // a second credential of the same merchant shares the same budget
        assertThat(resolver.resolve(exchange(token("mch_def", merchantId))).block()).isEqualTo("merchant:" + merchantId);
    }

    @Test
    void operatorTokensCountAgainstTheirClient() {
        assertThat(resolver.resolve(exchange(token("payflow-admin", null))).block()).isEqualTo("client:payflow-admin");
    }

    @Test
    void anonymousRequestsCountAgainstThePeerAddressNotXForwardedFor() {
        assertThat(resolver.resolve(exchange(null)).block()).isEqualTo("ip:203.0.113.7");
    }
}
