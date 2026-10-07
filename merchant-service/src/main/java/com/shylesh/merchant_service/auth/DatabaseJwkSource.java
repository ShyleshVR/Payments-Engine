package com.shylesh.merchant_service.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.shylesh.merchant_service.persistence.JwtSigningKey;
import com.shylesh.merchant_service.persistence.JwtSigningKeyRepository;
import com.shylesh.merchant_service.persistence.SigningKeyStatus;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Token signing keys, backed by the jwt_signing_keys table instead of a key generated in memory
 * at startup: every instance signs with the same key and tokens stay valid across restarts.
 * The first instance to start creates the key; a concurrent creator loses on the
 * one-ACTIVE-key unique index and reads the winner's key instead.
 *
 * The same source feeds the JWT encoder (private key) and the /oauth2/jwks endpoint (Spring
 * publishes only the public half). Used for local runs; in Kubernetes the key comes from a
 * Secret instead (PemFileJwkSource). Chosen in SigningKeyConfig.
 */
@RequiredArgsConstructor
@Slf4j
public class DatabaseJwkSource implements JWKSource<SecurityContext> {

    private static final int RSA_KEY_BITS = 2048;

    private final JwtSigningKeyRepository repository;
    private final TransactionTemplate transactionTemplate;

    private volatile JWKSet jwkSet;

    @Override
    public List<JWK> get(JWKSelector selector, SecurityContext context) {
        return selector.select(jwkSet());
    }

    JWKSet jwkSet() {
        JWKSet current = jwkSet;
        if (current == null) {
            synchronized (this) {
                if (jwkSet == null) {
                    jwkSet = new JWKSet(toRsaKey(loadOrCreateActiveKey()));
                }
                current = jwkSet;
            }
        }
        return current;
    }

    private JwtSigningKey loadOrCreateActiveKey() {
        return repository.findByStatus(SigningKeyStatus.ACTIVE).orElseGet(() -> {
            try {
                JwtSigningKey created = transactionTemplate.execute(status -> repository.saveAndFlush(generate()));
                log.info("Generated new JWT signing key. kid={}", created.getKid());
                return created;
            } catch (DataIntegrityViolationException e) {
                return repository.findByStatus(SigningKeyStatus.ACTIVE).orElseThrow(() -> e);
            }
        });
    }

    private static JwtSigningKey generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(RSA_KEY_BITS);
            KeyPair keyPair = generator.generateKeyPair();
            Base64.Encoder base64 = Base64.getEncoder();
            return JwtSigningKey.builder()
                    .kid(UUID.randomUUID().toString())
                    .publicKey(base64.encodeToString(keyPair.getPublic().getEncoded()))
                    .privateKey(base64.encodeToString(keyPair.getPrivate().getEncoded()))
                    .status(SigningKeyStatus.ACTIVE)
                    .createdAt(LocalDateTime.now())
                    .build();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to generate RSA signing key", e);
        }
    }

    static RSAKey toRsaKey(JwtSigningKey key) {
        try {
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            Base64.Decoder base64 = Base64.getDecoder();
            RSAPublicKey publicKey = (RSAPublicKey) keyFactory.generatePublic(
                    new X509EncodedKeySpec(base64.decode(key.getPublicKey())));
            RSAPrivateKey privateKey = (RSAPrivateKey) keyFactory.generatePrivate(
                    new PKCS8EncodedKeySpec(base64.decode(key.getPrivateKey())));
            return new RSAKey.Builder(publicKey)
                    .privateKey(privateKey)
                    .keyID(key.getKid())
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .build();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Stored JWT signing key " + key.getKid() + " is unreadable", e);
        }
    }
}
