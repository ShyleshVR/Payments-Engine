package com.shylesh.merchant_service.auth;

import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.stereotype.Component;

/**
 * Issued tokens are not stored. Access tokens are self-contained JWTs that every service
 * verifies against the published keys, and this server issues no refresh tokens and offers no
 * introspection, so nothing ever needs to look a token up again.
 *
 * Spring's default (InMemoryOAuth2AuthorizationService) would keep one entry per issued token
 * forever. The trade-off: a single token can't be revoked; revoking its credential stops new
 * tokens and the existing one expires within the access token TTL.
 */
@Component
public class StatelessAuthorizationService implements OAuth2AuthorizationService {

    @Override
    public void save(OAuth2Authorization authorization) {
    }

    @Override
    public void remove(OAuth2Authorization authorization) {
    }

    @Override
    public OAuth2Authorization findById(String id) {
        return null;
    }

    @Override
    public OAuth2Authorization findByToken(String token, OAuth2TokenType tokenType) {
        return null;
    }
}
