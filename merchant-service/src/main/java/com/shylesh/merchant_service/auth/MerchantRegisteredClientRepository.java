package com.shylesh.merchant_service.auth;

import com.shylesh.merchant_service.config.AuthProperties;
import com.shylesh.merchant_service.persistence.Merchant;
import com.shylesh.merchant_service.persistence.MerchantCredential;
import com.shylesh.merchant_service.persistence.MerchantCredentialRepository;
import com.shylesh.merchant_service.persistence.MerchantRepository;
import com.shylesh.merchant_service.security.Scopes;

import lombok.extern.slf4j.Slf4j;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/**
 * Spring Authorization Server's view of who may obtain tokens, read live from the merchant
 * tables rather than registered up front: a credential can authenticate only while it is
 * ACTIVE and its merchant is ACTIVE. Revoking a credential or suspending a merchant therefore
 * blocks the very next token request (invalid_client); tokens already issued run out within
 * the access token TTL.
 *
 * Also serves the bootstrap admin client from configuration, which onboards merchants and
 * performs internal operations.
 */
@Component
@Slf4j
public class MerchantRegisteredClientRepository implements RegisteredClientRepository {

    /** Client setting carrying the merchant id into the token customizer. */
    public static final String MERCHANT_ID_SETTING = "payflow.merchant-id";

    private static final String ADMIN_REGISTRATION_ID = "bootstrap-admin";
    private static final String LOCAL_DEV_ADMIN_SECRET = "local-dev-admin-secret";

    private final MerchantCredentialRepository credentialRepository;
    private final MerchantRepository merchantRepository;
    private final AuthProperties properties;
    private final RegisteredClient adminClient;

    public MerchantRegisteredClientRepository(
            MerchantCredentialRepository credentialRepository,
            MerchantRepository merchantRepository,
            AuthProperties properties,
            PasswordEncoder passwordEncoder) {
        this.credentialRepository = credentialRepository;
        this.merchantRepository = merchantRepository;
        this.properties = properties;

        AuthProperties.BootstrapAdmin admin = properties.bootstrapAdmin();
        if (admin == null || isBlank(admin.clientId()) || isBlank(admin.clientSecret())) {
            throw new IllegalStateException("payflow.auth.bootstrap-admin.client-id and client-secret must be set");
        }
        if (LOCAL_DEV_ADMIN_SECRET.equals(admin.clientSecret())) {
            log.warn("Bootstrap admin client is using the local development secret; set PAYFLOW_ADMIN_CLIENT_SECRET outside local development");
        }
        this.adminClient = client(ADMIN_REGISTRATION_ID, admin.clientId(), passwordEncoder.encode(admin.clientSecret()), Scopes.ADMIN, null);
    }

    @Override
    public void save(RegisteredClient registeredClient) {
        throw new UnsupportedOperationException("Clients are managed through the merchant admin API");
    }

    @Override
    public RegisteredClient findById(String id) {
        if (adminClient.getId().equals(id)) {
            return adminClient;
        }
        UUID credentialId;
        try {
            credentialId = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            return null;
        }
        return credentialRepository.findById(credentialId).map(this::toRegisteredClient).orElse(null);
    }

    @Override
    public RegisteredClient findByClientId(String clientId) {
        if (adminClient.getClientId().equals(clientId)) {
            return adminClient;
        }
        return credentialRepository.findByClientId(clientId).map(this::toRegisteredClient).orElse(null);
    }

    private RegisteredClient toRegisteredClient(MerchantCredential credential) {
        if (!credential.isActive()) {
            return null;
        }
        Merchant merchant = merchantRepository.findById(credential.getMerchantId()).orElse(null);
        if (merchant == null || !merchant.isActive()) {
            return null;
        }
        return client(credential.getId().toString(), credential.getClientId(), credential.getClientSecretHash(),
                Scopes.MERCHANT, merchant.getId());
    }

    private RegisteredClient client(String id, String clientId, String secretHash, Set<String> scopes, UUID merchantId) {
        ClientSettings.Builder clientSettings = ClientSettings.builder().requireAuthorizationConsent(false);
        if (merchantId != null) {
            clientSettings.setting(MERCHANT_ID_SETTING, merchantId.toString());
        }

        return RegisteredClient.withId(id)
                .clientId(clientId)
                .clientSecret(secretHash)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scopes(granted -> granted.addAll(scopes))
                .clientSettings(clientSettings.build())
                .tokenSettings(TokenSettings.builder()
                        .accessTokenFormat(OAuth2TokenFormat.SELF_CONTAINED)
                        .accessTokenTimeToLive(properties.accessTokenTtl())
                        .build())
                .build();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
