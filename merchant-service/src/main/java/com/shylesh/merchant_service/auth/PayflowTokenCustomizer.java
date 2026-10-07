package com.shylesh.merchant_service.auth;

import com.shylesh.merchant_service.config.AuthProperties;
import com.shylesh.merchant_service.security.Scopes;

import lombok.RequiredArgsConstructor;

import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Shapes every access token PayFlow APIs accept:
 * - aud = payflow-api, so a token minted for something else is rejected;
 * - merchant_id for merchant clients: services take the merchant from here, never from the request;
 * - scope defaults to everything the client is allowed when it asks for none (Spring grants an
 *   empty scope set in that case); a client can still request a subset for least privilege.
 */
@Component
@RequiredArgsConstructor
public class PayflowTokenCustomizer implements OAuth2TokenCustomizer<JwtEncodingContext> {

    private static final String SCOPE_CLAIM = "scope";

    private final AuthProperties properties;

    @Override
    public void customize(JwtEncodingContext context) {
        if (!OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
            return;
        }

        RegisteredClient client = context.getRegisteredClient();
        context.getClaims().audience(List.of(properties.audience()));

        if (context.getAuthorizedScopes().isEmpty()) {
            context.getClaims().claim(SCOPE_CLAIM, client.getScopes());
        }

        String merchantId = client.getClientSettings().getSetting(MerchantRegisteredClientRepository.MERCHANT_ID_SETTING);
        if (merchantId != null) {
            context.getClaims().claim(Scopes.MERCHANT_ID_CLAIM, merchantId);
        }
    }
}
