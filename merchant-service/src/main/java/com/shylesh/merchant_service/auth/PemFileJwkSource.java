package com.shylesh.merchant_service.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;

import org.springframework.core.io.Resource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.List;

/**
 * Signing key loaded from a PKCS#8 PEM file, e.g. a Kubernetes Secret mounted into the pod, so the
 * private key never sits in a database row. Every replica mounting the same Secret signs with the
 * same key. The kid is the key's RFC 7638 thumbprint: stable across restarts and replicas, and a
 * new key automatically gets a new kid (which makes resource servers refetch the JWKS).
 *
 * Generate one with: openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out signing-key.pem
 */
public final class PemFileJwkSource implements JWKSource<SecurityContext> {

    private static final String PKCS8_HEADER = "-----BEGIN PRIVATE KEY-----";
    private static final String PKCS1_HEADER = "-----BEGIN RSA PRIVATE KEY-----";

    private final JWKSet jwkSet;

    private PemFileJwkSource(RSAKey key) {
        this.jwkSet = new JWKSet(key);
    }

    public static PemFileJwkSource load(Resource resource) {
        try {
            return new PemFileJwkSource(rsaKey(resource.getContentAsString(StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read JWT signing key from " + resource.getDescription(), e);
        }
    }

    static RSAKey rsaKey(String pem) {
        if (pem.contains(PKCS1_HEADER)) {
            throw new IllegalStateException("Signing key must be PKCS#8 (BEGIN PRIVATE KEY); convert with: "
                    + "openssl pkcs8 -topk8 -nocrypt -in key.pem -out signing-key.pem");
        }
        if (!pem.contains(PKCS8_HEADER)) {
            throw new IllegalStateException("Signing key file is not a PKCS#8 PEM private key");
        }

        String base64 = pem.replaceAll("-----(BEGIN|END) PRIVATE KEY-----", "").replaceAll("\\s", "");
        try {
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            RSAPrivateCrtKey privateKey = (RSAPrivateCrtKey) keyFactory.generatePrivate(
                    new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
            RSAPublicKey publicKey = (RSAPublicKey) keyFactory.generatePublic(
                    new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent()));

            RSAKey withoutKid = new RSAKey.Builder(publicKey)
                    .privateKey(privateKey)
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .build();
            return new RSAKey.Builder(withoutKid).keyID(withoutKid.computeThumbprint().toString()).build();
        } catch (GeneralSecurityException | ClassCastException | IllegalArgumentException | JOSEException e) {
            throw new IllegalStateException("Signing key file does not contain a usable RSA private key", e);
        }
    }

    @Override
    public List<JWK> get(JWKSelector selector, SecurityContext context) {
        return selector.select(jwkSet);
    }
}
