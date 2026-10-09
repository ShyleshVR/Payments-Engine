package com.shylesh.merchant_service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * @param issuer               iss claim and the base URL resource servers validate against
 * @param audience             aud claim every PayFlow API requires
 * @param accessTokenTtl       access token lifetime; also the window in which a revoked credential's
 *                             already-issued tokens keep working
 * @param maxActiveCredentials active credentials per merchant (2 = rotate without downtime)
 * @param bootstrapAdmin       the operator client that onboards merchants and runs internal operations
 * @param serviceClients       internal services that call other services' APIs (e.g. the daily
 *                             reconciliation), each with only the operator scopes it needs
 */
@ConfigurationProperties(prefix = "payflow.auth")
public record AuthProperties(
        String issuer,
        @DefaultValue("payflow-api") String audience,
        @DefaultValue("15m") Duration accessTokenTtl,
        @DefaultValue("2") int maxActiveCredentials,
        BootstrapAdmin bootstrapAdmin,
        @DefaultValue List<ServiceClient> serviceClients
) {

    public record BootstrapAdmin(String clientId, String clientSecret) {
    }

    /** A client whose secret is blank is not registered (e.g. a local run without that service). */
    public record ServiceClient(String clientId, String clientSecret, Set<String> scopes) {
    }
}
