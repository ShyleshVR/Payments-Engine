package com.shylesh.merchant_service.auth;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.shylesh.merchant_service.persistence.JwtSigningKeyRepository;

import lombok.extern.slf4j.Slf4j;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

/**
 * Chooses where the token signing key comes from:
 * - payflow.auth.signing-key.location set (Kubernetes: a mounted Secret) -> PEM file;
 * - otherwise (local runs) -> a key generated once into the jwt_signing_keys table.
 * Either way the key is loaded at startup, so a missing or broken key fails the boot instead of
 * the first token request.
 */
@Configuration
@Slf4j
public class SigningKeyConfig {

    @Bean
    public JWKSource<SecurityContext> jwkSource(SigningKeyProperties signingKey,
                                                JwtSigningKeyRepository repository,
                                                TransactionTemplate transactionTemplate,
                                                ResourceLoader resourceLoader) {
        if (StringUtils.hasText(signingKey.location())) {
            PemFileJwkSource source = PemFileJwkSource.load(resourceLoader.getResource(signingKey.location()));
            log.info("JWT signing key loaded from {}", signingKey.location());
            return source;
        }

        DatabaseJwkSource source = new DatabaseJwkSource(repository, transactionTemplate);
        source.jwkSet();
        return source;
    }
}
