package com.shylesh.merchant_service.service.impl;

import com.shylesh.merchant_service.config.AuthProperties;
import com.shylesh.merchant_service.dto.CreateMerchantRequest;
import com.shylesh.merchant_service.dto.CreateMerchantResponse;
import com.shylesh.merchant_service.exception.CredentialNotFoundException;
import com.shylesh.merchant_service.exception.MerchantConflictException;
import com.shylesh.merchant_service.exception.MerchantNotFoundException;
import com.shylesh.merchant_service.persistence.*;
import com.shylesh.merchant_service.service.CredentialGenerator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MerchantAdminServiceImplTest {

    private MerchantRepository merchantRepository;
    private MerchantCredentialRepository credentialRepository;
    private final PasswordEncoder passwordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
    private MerchantAdminServiceImpl service;

    @BeforeEach
    void setUp() {
        merchantRepository = mock(MerchantRepository.class);
        credentialRepository = mock(MerchantCredentialRepository.class);
        AuthProperties properties = new AuthProperties("http://localhost:8084", "payflow-api", Duration.ofMinutes(15), 2,
                new AuthProperties.BootstrapAdmin("payflow-admin", "secret"), java.util.List.of());
        service = new MerchantAdminServiceImpl(merchantRepository, credentialRepository, new CredentialGenerator(),
                passwordEncoder, properties);
    }

    private Merchant merchant(MerchantStatus status) {
        return Merchant.builder().id(UUID.randomUUID()).name("Acme").email("ops@acme.test").status(status)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
    }

    @Test
    void onboardingReturnsTheSecretOnceAndStoresOnlyABcryptHash() {
        CreateMerchantResponse created = service.createMerchant(new CreateMerchantRequest("Acme", "ops@acme.test"));

        assertThat(created.status()).isEqualTo(MerchantStatus.ACTIVE);
        assertThat(created.credential().clientId()).matches("mch_[0-9a-f]{16}");
        assertThat(created.credential().clientSecret()).matches("sk_[0-9a-f]{64}");

        ArgumentCaptor<MerchantCredential> stored = ArgumentCaptor.forClass(MerchantCredential.class);
        verify(credentialRepository).save(stored.capture());
        assertThat(stored.getValue().getClientSecretHash())
                .startsWith("{bcrypt}")
                .doesNotContain(created.credential().clientSecret());
        assertThat(passwordEncoder.matches(created.credential().clientSecret(), stored.getValue().getClientSecretHash())).isTrue();
    }

    @Test
    void duplicateEmailIsAConflict() {
        when(merchantRepository.existsByEmailIgnoreCase("ops@acme.test")).thenReturn(true);

        assertThatThrownBy(() -> service.createMerchant(new CreateMerchantRequest("Acme", "ops@acme.test")))
                .isInstanceOf(MerchantConflictException.class);
        verify(merchantRepository, never()).saveAndFlush(any());
    }

    @Test
    void issuingBeyondTheActiveCredentialLimitIsAConflict() {
        Merchant merchant = merchant(MerchantStatus.ACTIVE);
        when(merchantRepository.findByIdForUpdate(merchant.getId())).thenReturn(Optional.of(merchant));
        when(credentialRepository.countByMerchantIdAndStatus(merchant.getId(), CredentialStatus.ACTIVE)).thenReturn(2L);

        assertThatThrownBy(() -> service.issueCredential(merchant.getId()))
                .isInstanceOf(MerchantConflictException.class)
                .hasMessageContaining("2 active credentials");
        verify(credentialRepository, never()).save(any());
    }

    @Test
    void secondCredentialIsIssuedUnderTheLimit() {
        Merchant merchant = merchant(MerchantStatus.ACTIVE);
        when(merchantRepository.findByIdForUpdate(merchant.getId())).thenReturn(Optional.of(merchant));
        when(credentialRepository.countByMerchantIdAndStatus(merchant.getId(), CredentialStatus.ACTIVE)).thenReturn(1L);

        assertThat(service.issueCredential(merchant.getId()).clientSecret()).startsWith("sk_");
    }

    @Test
    void suspendedMerchantGetsNoNewCredentials() {
        Merchant merchant = merchant(MerchantStatus.SUSPENDED);
        when(merchantRepository.findByIdForUpdate(merchant.getId())).thenReturn(Optional.of(merchant));

        assertThatThrownBy(() -> service.issueCredential(merchant.getId())).isInstanceOf(MerchantConflictException.class);
    }

    @Test
    void revokeIsIdempotentAndScopedToTheMerchant() {
        UUID merchantId = UUID.randomUUID();
        MerchantCredential credential = MerchantCredential.builder().id(UUID.randomUUID()).merchantId(merchantId)
                .clientId("mch_1").clientSecretHash("{bcrypt}x").status(CredentialStatus.ACTIVE).createdAt(LocalDateTime.now()).build();
        when(credentialRepository.findByClientIdAndMerchantId("mch_1", merchantId)).thenReturn(Optional.of(credential));

        service.revokeCredential(merchantId, "mch_1");
        LocalDateTime revokedAt = credential.getRevokedAt();
        service.revokeCredential(merchantId, "mch_1");

        assertThat(credential.getStatus()).isEqualTo(CredentialStatus.REVOKED);
        assertThat(credential.getRevokedAt()).isEqualTo(revokedAt);
        assertThatThrownBy(() -> service.revokeCredential(UUID.randomUUID(), "mch_1"))
                .isInstanceOf(CredentialNotFoundException.class);
    }

    @Test
    void unknownMerchantIsNotFound() {
        assertThatThrownBy(() -> service.getMerchant(UUID.randomUUID())).isInstanceOf(MerchantNotFoundException.class);
    }
}
