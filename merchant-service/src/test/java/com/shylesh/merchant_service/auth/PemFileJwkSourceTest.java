package com.shylesh.merchant_service.auth;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.RSAKey;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PemFileJwkSourceTest {

    @TempDir
    Path dir;

    static String pkcs8Pem(KeyPair keyPair) {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(keyPair.getPrivate().getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n" + base64 + "\n-----END PRIVATE KEY-----\n";
    }

    static KeyPair newKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private List<JWK> keys(PemFileJwkSource source) {
        return source.get(new JWKSelector(new JWKMatcher.Builder().build()), null);
    }

    @Test
    void loadsAPkcs8KeyWithItsThumbprintAsKid() throws Exception {
        Path file = dir.resolve("signing-key.pem");
        Files.writeString(file, pkcs8Pem(newKeyPair()));

        RSAKey key = (RSAKey) keys(PemFileJwkSource.load(new FileSystemResource(file))).get(0);

        assertThat(key.isPrivate()).isTrue();
        assertThat(key.getKeyID()).isEqualTo(key.computeThumbprint().toString());
        assertThat(key.getAlgorithm().getName()).isEqualTo("RS256");
    }

    @Test
    void sameFileGivesTheSameKidEveryTime() throws Exception {
        Path file = dir.resolve("signing-key.pem");
        Files.writeString(file, pkcs8Pem(newKeyPair()));

        String first = keys(PemFileJwkSource.load(new FileSystemResource(file))).get(0).getKeyID();
        String second = keys(PemFileJwkSource.load(new FileSystemResource(file))).get(0).getKeyID();

        assertThat(first).isEqualTo(second);
    }

    @Test
    void rejectsPkcs1WithAConversionHint() {
        assertThatThrownBy(() -> PemFileJwkSource.rsaKey("-----BEGIN RSA PRIVATE KEY-----\nAAAA\n-----END RSA PRIVATE KEY-----"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("openssl pkcs8");
    }

    @Test
    void rejectsGarbage() {
        assertThatThrownBy(() -> PemFileJwkSource.rsaKey("-----BEGIN PRIVATE KEY-----\nbm90IGEga2V5\n-----END PRIVATE KEY-----"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> PemFileJwkSource.rsaKey("hello"))
                .isInstanceOf(IllegalStateException.class);
    }
}
