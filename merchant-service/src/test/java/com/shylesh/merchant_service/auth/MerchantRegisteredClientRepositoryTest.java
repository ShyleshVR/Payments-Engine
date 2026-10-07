package com.shylesh.merchant_service.auth;

import com.shylesh.merchant_service.config.AuthProperties;
import com.shylesh.merchant_service.persistence.*;
import com.shylesh.merchant_service.security.Scopes;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class MerchantRegisteredClientRepositoryTest {

    private final PasswordEncoder passwordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
    private MerchantCredentialRepository credentialRepository;
    private MerchantRepository merchantRepository;
    private MerchantRegisteredClientRepository repository;

    private Merchant merchant;
    private MerchantCredential credential;

    @BeforeEach
    void setUp() {
        credentialRepository = mock(MerchantCredentialRepository.class);
        merchantRepository = mock(MerchantRepository.class);
        AuthProperties properties = new AuthProperties("http://localhost:8084", "payflow-api", Duration.ofMinutes(15), 2,
                new AuthProperties.BootstrapAdmin("payflow-admin", "admin-secret"));
        repository = new MerchantRegisteredClientRepository(credentialRepository, merchantRepository, properties, passwordEncoder);

        merchant = Merchant.builder().id(UUID.randomUUID()).name("Acme").email("a@acme.test").status(MerchantStatus.ACTIVE)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        credential = MerchantCredential.builder().id(UUID.randomUUID()).merchantId(merchant.getId()).clientId("mch_abc")
                .clientSecretHash("{bcrypt}hash").status(CredentialStatus.ACTIVE).createdAt(LocalDateTime.now()).build();
        when(credentialRepository.findByClientId("mch_abc")).thenReturn(Optional.of(credential));
        when(credentialRepository.findById(credential.getId())).thenReturn(Optional.of(credential));
        when(merchantRepository.findById(merchant.getId())).thenReturn(Optional.of(merchant));
    }

    @Test
    void activeCredentialOfActiveMerchantIsAClientCredentialsClientBoundToTheMerchant() {
        RegisteredClient client = repository.findByClientId("mch_abc");

        assertThat(client).isNotNull();
        assertThat(client.getAuthorizationGrantTypes()).containsExactly(AuthorizationGrantType.CLIENT_CREDENTIALS);
        assertThat(client.getScopes()).isEqualTo(Scopes.MERCHANT);
        assertThat(client.getTokenSettings().getAccessTokenTimeToLive()).isEqualTo(Duration.ofMinutes(15));
        assertThat((String) client.getClientSettings().getSetting(MerchantRegisteredClientRepository.MERCHANT_ID_SETTING))
                .isEqualTo(merchant.getId().toString());
        assertThat(repository.findById(credential.getId().toString())).isNotNull();
    }

    @Test
    void revokedCredentialCannotAuthenticate() {
        credential.revoke(LocalDateTime.now());

        assertThat(repository.findByClientId("mch_abc")).isNull();
    }

    @Test
    void suspendedMerchantCannotAuthenticate() {
        merchant.suspend(LocalDateTime.now());

        assertThat(repository.findByClientId("mch_abc")).isNull();
    }

    @Test
    void bootstrapAdminHasOperatorScopesAndNoMerchant() {
        RegisteredClient admin = repository.findByClientId("payflow-admin");

        assertThat(admin.getScopes()).isEqualTo(Scopes.ADMIN);
        assertThat((String) admin.getClientSettings().getSetting(MerchantRegisteredClientRepository.MERCHANT_ID_SETTING)).isNull();
        assertThat(passwordEncoder.matches("admin-secret", admin.getClientSecret())).isTrue();
    }

    @Test
    void unknownClientAndForeignIdsResolveToNull() {
        assertThat(repository.findByClientId("mch_nope")).isNull();
        assertThat(repository.findById("not-a-uuid")).isNull();
    }

    @Test
    void clientsCannotBeRegisteredThroughSpring() {
        assertThatThrownBy(() -> repository.save(repository.findByClientId("payflow-admin")))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
