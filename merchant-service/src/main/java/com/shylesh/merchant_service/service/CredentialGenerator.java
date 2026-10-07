package com.shylesh.merchant_service.service;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * client_id: "mch_" + 16 hex chars (64 random bits; public, only needs to be unique).
 * client_secret: "sk_" + 64 hex chars (256 random bits).
 */
@Component
public class CredentialGenerator {

    private static final String CLIENT_ID_PREFIX = "mch_";
    private static final String SECRET_PREFIX = "sk_";

    private final SecureRandom secureRandom = new SecureRandom();

    public String newClientId() {
        return CLIENT_ID_PREFIX + randomHex(8);
    }

    public String newClientSecret() {
        return SECRET_PREFIX + randomHex(32);
    }

    private String randomHex(int bytes) {
        byte[] buffer = new byte[bytes];
        secureRandom.nextBytes(buffer);
        return HexFormat.of().formatHex(buffer);
    }
}
