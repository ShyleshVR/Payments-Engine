package com.shylesh.merchant_service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.RSAKey;
import com.shylesh.merchant_service.persistence.JwtSigningKeyRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** The authorization server signing with a key from a PEM file (as mounted from a Kubernetes Secret). */
@SpringBootTest(properties = "management.tracing.enabled=false")
@AutoConfigureMockMvc
@Testcontainers
class PemSigningKeyIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    static KeyPair keyPair;

    @DynamicPropertySource
    static void signingKey(DynamicPropertyRegistry registry) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
        Path file = Files.createTempFile("signing-key", ".pem");
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(keyPair.getPrivate().getEncoded());
        Files.writeString(file, "-----BEGIN PRIVATE KEY-----\n" + base64 + "\n-----END PRIVATE KEY-----\n");
        file.toFile().deleteOnExit();
        registry.add("payflow.auth.signing-key.location", () -> file.toUri().toString());
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtSigningKeyRepository signingKeyRepository;

    @Test
    void tokensAreSignedWithThePemKeyAndNoKeyIsStoredInTheDatabase() throws Exception {
        String basic = "Basic " + Base64.getEncoder().encodeToString("payflow-admin:local-dev-admin-secret".getBytes(StandardCharsets.UTF_8));
        String token = objectMapper.readTree(mockMvc.perform(post("/oauth2/token")
                        .header(HttpHeaders.AUTHORIZATION, basic)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "client_credentials"))
                .andReturn().getResponse().getContentAsString()).get("access_token").asText();

        String expectedKid = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic()).build().computeThumbprint().toString();
        JsonNode header = objectMapper.readTree(Base64.getUrlDecoder().decode(token.split("\\.")[0]));
        assertThat(header.get("kid").asText()).isEqualTo(expectedKid);

        JsonNode jwks = objectMapper.readTree(mockMvc.perform(get("/oauth2/jwks")).andReturn().getResponse().getContentAsString());
        assertThat(jwks.get("keys").get(0).get("kid").asText()).isEqualTo(expectedKid);
        assertThat(jwks.get("keys").get(0).has("d")).isFalse();

        assertThat(signingKeyRepository.count()).as("PEM mode must not create a DB key").isZero();
    }
}
