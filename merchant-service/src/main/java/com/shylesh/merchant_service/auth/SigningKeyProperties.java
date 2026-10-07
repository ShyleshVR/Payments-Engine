package com.shylesh.merchant_service.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param location Spring resource (e.g. file:/etc/payflow/signing-key/signing-key.pem) of a PKCS#8 PEM
 *                 RSA private key. When set, tokens are signed with it; when empty (local runs), a key
 *                 generated into the jwt_signing_keys table is used instead.
 */
@ConfigurationProperties(prefix = "payflow.auth.signing-key")
public record SigningKeyProperties(String location) {
}
