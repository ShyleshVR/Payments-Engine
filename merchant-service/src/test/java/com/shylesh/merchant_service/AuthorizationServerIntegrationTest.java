package com.shylesh.merchant_service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shylesh.merchant_service.persistence.JwtSigningKeyRepository;
import com.shylesh.merchant_service.persistence.SigningKeyStatus;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * The real authorization server against a real Postgres (Testcontainers): token issuance,
 * claims, scope enforcement on the admin API, revocation, suspension, rotation limit, JWKS.
 */
@SpringBootTest(properties = {
        "management.tracing.enabled=false",
        // a list property is replaced as a whole, so the entry is given in full
        "payflow.auth.service-clients[0].client-id=reconciliation-service",
        "payflow.auth.service-clients[0].client-secret=recon-secret",
        "payflow.auth.service-clients[0].scopes=ledger:admin,payments:audit"
})
@AutoConfigureMockMvc
@Testcontainers
class AuthorizationServerIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    private static final String ADMIN_ID = "payflow-admin";
    private static final String ADMIN_SECRET = "local-dev-admin-secret";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtSigningKeyRepository signingKeyRepository;

    @Autowired
    private io.micrometer.core.instrument.MeterRegistry meterRegistry;

    private static String basic(String id, String secret) {
        return "Basic " + Base64.getEncoder().encodeToString((id + ":" + secret).getBytes(StandardCharsets.UTF_8));
    }

    private MvcResult tokenRequest(String clientId, String secret, String scope) throws Exception {
        var request = post("/oauth2/token")
                .header(HttpHeaders.AUTHORIZATION, basic(clientId, secret))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", "client_credentials");
        if (scope != null) {
            request.param("scope", scope);
        }
        return mockMvc.perform(request).andReturn();
    }

    private String token(String clientId, String secret) throws Exception {
        MvcResult result = tokenRequest(clientId, secret, null);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("access_token").asText();
    }

    private JsonNode claims(String jwt) throws Exception {
        return objectMapper.readTree(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]));
    }

    private JsonNode jwtHeader(String jwt) throws Exception {
        return objectMapper.readTree(Base64.getUrlDecoder().decode(jwt.split("\\.")[0]));
    }

    private JsonNode createMerchant(String adminToken) throws Exception {
        String body = mockMvc.perform(post("/api/v1/merchants")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Acme\",\"email\":\"ops-" + UUID.randomUUID() + "@acme.test\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    @Test
    void adminTokenCarriesOperatorScopesAudienceAndNoMerchant() throws Exception {
        JsonNode claims = claims(token(ADMIN_ID, ADMIN_SECRET));

        assertThat(claims.get("iss").asText()).isEqualTo("http://localhost:8084");
        assertThat(claims.get("aud").toString()).contains("payflow-api");
        assertThat(claims.get("scope").toString()).contains("merchants:admin", "payments:operate", "ledger:admin");
        assertThat(claims.has("merchant_id")).isFalse();
    }

    @Test
    void serviceClientGetsOnlyItsReadOnlyScopes() throws Exception {
        String serviceToken = token("reconciliation-service", "recon-secret");
        JsonNode claims = claims(serviceToken);

        assertThat(claims.get("scope").toString()).contains("ledger:admin", "payments:audit")
                .doesNotContain("merchants:admin", "payments:operate", "payments:write");
        assertThat(claims.has("merchant_id")).isFalse();
        assertThat(tokenRequest("reconciliation-service", "recon-secret", "merchants:admin").getResponse().getStatus()).isEqualTo(400);
        assertThat(mockMvc.perform(get("/api/v1/merchants/" + UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceToken)).andReturn().getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void onboardedMerchantGetsA15MinuteTokenBoundToItself() throws Exception {
        JsonNode merchant = createMerchant(token(ADMIN_ID, ADMIN_SECRET));
        String clientId = merchant.at("/credential/clientId").asText();
        String secret = merchant.at("/credential/clientSecret").asText();

        JsonNode claims = claims(token(clientId, secret));

        assertThat(claims.get("merchant_id").asText()).isEqualTo(merchant.get("merchantId").asText());
        assertThat(claims.get("sub").asText()).isEqualTo(clientId);
        assertThat(claims.get("scope").toString()).contains("payments:write", "payments:read", "ledger:read", "webhooks:manage",
                        "payouts:read", "payouts:write")
                .doesNotContain("payments:operate");
        long ttl = claims.get("exp").asLong() - claims.get("iat").asLong();
        assertThat(ttl).isEqualTo(15 * 60);
        assertThat(claims.get("exp").asLong()).isGreaterThan(Instant.now().getEpochSecond());
    }

    @Test
    void merchantCanAskForASubsetOfItsScopesButNotMore() throws Exception {
        JsonNode merchant = createMerchant(token(ADMIN_ID, ADMIN_SECRET));
        String clientId = merchant.at("/credential/clientId").asText();
        String secret = merchant.at("/credential/clientSecret").asText();

        MvcResult subset = tokenRequest(clientId, secret, "payments:read");
        String jwt = objectMapper.readTree(subset.getResponse().getContentAsString()).get("access_token").asText();
        assertThat(claims(jwt).get("scope").toString()).isEqualTo("[\"payments:read\"]");

        assertThat(tokenRequest(clientId, secret, "payments:operate").getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void wrongSecretIsInvalidClient() throws Exception {
        MvcResult result = tokenRequest(ADMIN_ID, "wrong", null);

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(result.getResponse().getContentAsString()).contains("invalid_client");
    }

    @Test
    void adminApiRejectsMissingTokensAndMerchantTokensWithJsonErrors() throws Exception {
        mockMvc.perform(get("/api/v1/merchants/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, org.hamcrest.Matchers.startsWith("Bearer")))
                .andExpect(jsonPath("$.status").value(401));

        JsonNode merchant = createMerchant(token(ADMIN_ID, ADMIN_SECRET));
        String merchantToken = token(merchant.at("/credential/clientId").asText(), merchant.at("/credential/clientSecret").asText());
        mockMvc.perform(get("/api/v1/merchants/" + merchant.get("merchantId").asText())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + merchantToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        mockMvc.perform(get("/api/v1/merchants/" + merchant.get("merchantId").asText())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + merchantToken + "tampered"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void revokedCredentialAndSuspendedMerchantCannotGetTokens() throws Exception {
        String adminToken = token(ADMIN_ID, ADMIN_SECRET);
        JsonNode merchant = createMerchant(adminToken);
        String merchantId = merchant.get("merchantId").asText();
        String clientId = merchant.at("/credential/clientId").asText();
        String secret = merchant.at("/credential/clientSecret").asText();

        mockMvc.perform(post("/api/v1/merchants/" + merchantId + "/suspend").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENDED"));
        assertThat(tokenRequest(clientId, secret, null).getResponse().getStatus()).isEqualTo(401);

        mockMvc.perform(post("/api/v1/merchants/" + merchantId + "/activate").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());
        assertThat(tokenRequest(clientId, secret, null).getResponse().getStatus()).isEqualTo(200);

        mockMvc.perform(delete("/api/v1/merchants/" + merchantId + "/credentials/" + clientId).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isNoContent());
        assertThat(tokenRequest(clientId, secret, null).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void rotationAllowsTwoActiveCredentialsNotThree() throws Exception {
        String adminToken = token(ADMIN_ID, ADMIN_SECRET);
        JsonNode merchant = createMerchant(adminToken);
        String merchantId = merchant.get("merchantId").asText();

        String second = mockMvc.perform(post("/api/v1/merchants/" + merchantId + "/credentials")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode secondCredential = objectMapper.readTree(second);
        assertThat(tokenRequest(secondCredential.get("clientId").asText(), secondCredential.get("clientSecret").asText(), null)
                .getResponse().getStatus()).isEqualTo(200);

        mockMvc.perform(post("/api/v1/merchants/" + merchantId + "/credentials").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isConflict());

        mockMvc.perform(get("/api/v1/merchants/" + merchantId).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(jsonPath("$.credentials.length()").value(2))
                .andExpect(jsonPath("$.credentials[0].clientSecret").doesNotExist());
    }

    @Test
    void jwksPublishesOnlyThePublicHalfOfThePersistedSigningKey() throws Exception {
        String jwt = token(ADMIN_ID, ADMIN_SECRET);
        String kid = jwtHeader(jwt).get("kid").asText();

        JsonNode jwks = objectMapper.readTree(mockMvc.perform(get("/oauth2/jwks"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        JsonNode key = jwks.get("keys").get(0);
        assertThat(key.get("kid").asText()).isEqualTo(kid);
        assertThat(key.get("kty").asText()).isEqualTo("RSA");
        assertThat(key.has("d")).as("private exponent must never be published").isFalse();
        assertThat(signingKeyRepository.findByStatus(SigningKeyStatus.ACTIVE).orElseThrow().getKid()).isEqualTo(kid);
    }

    @Test
    void duplicateEmailIsAConflict() throws Exception {
        String adminToken = token(ADMIN_ID, ADMIN_SECRET);
        String body = "{\"name\":\"Dup\",\"email\":\"dup@acme.test\"}";
        mockMvc.perform(post("/api/v1/merchants").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/merchants").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body.replace("dup@", "DUP@")))
                .andExpect(status().isConflict());
    }

    @Test
    void tokenRequestsAreCountedByOutcome() throws Exception {
        double issuedBefore = meterRegistry.counter("auth.token.requests", "outcome", "issued").count();
        double invalidBefore = meterRegistry.counter("auth.token.requests", "outcome", "invalid_client").count();

        token(ADMIN_ID, ADMIN_SECRET);
        tokenRequest(ADMIN_ID, "wrong", null);

        assertThat(meterRegistry.counter("auth.token.requests", "outcome", "issued").count()).isEqualTo(issuedBefore + 1);
        assertThat(meterRegistry.counter("auth.token.requests", "outcome", "invalid_client").count()).isEqualTo(invalidBefore + 1);
    }
}
