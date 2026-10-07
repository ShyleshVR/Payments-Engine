package com.shylesh.merchant_service.service.impl;

import com.shylesh.merchant_service.config.AuthProperties;
import com.shylesh.merchant_service.dto.CreateMerchantRequest;
import com.shylesh.merchant_service.dto.CreateMerchantResponse;
import com.shylesh.merchant_service.dto.CredentialResponse;
import com.shylesh.merchant_service.dto.IssuedCredentialResponse;
import com.shylesh.merchant_service.dto.MerchantResponse;
import com.shylesh.merchant_service.exception.CredentialNotFoundException;
import com.shylesh.merchant_service.exception.MerchantConflictException;
import com.shylesh.merchant_service.exception.MerchantNotFoundException;
import com.shylesh.merchant_service.persistence.CredentialStatus;
import com.shylesh.merchant_service.persistence.Merchant;
import com.shylesh.merchant_service.persistence.MerchantCredential;
import com.shylesh.merchant_service.persistence.MerchantCredentialRepository;
import com.shylesh.merchant_service.persistence.MerchantRepository;
import com.shylesh.merchant_service.persistence.MerchantStatus;
import com.shylesh.merchant_service.service.CredentialGenerator;
import com.shylesh.merchant_service.service.MerchantAdminService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class MerchantAdminServiceImpl implements MerchantAdminService {

    private final MerchantRepository merchantRepository;
    private final MerchantCredentialRepository credentialRepository;
    private final CredentialGenerator credentialGenerator;
    private final PasswordEncoder passwordEncoder;
    private final AuthProperties properties;

    @Override
    @Transactional
    public CreateMerchantResponse createMerchant(CreateMerchantRequest request) {
        String email = request.email().trim();
        if (merchantRepository.existsByEmailIgnoreCase(email)) {
            throw new MerchantConflictException("A merchant with email " + email + " already exists");
        }

        LocalDateTime now = LocalDateTime.now();
        Merchant merchant = Merchant.builder()
                .id(UUID.randomUUID())
                .name(request.name().trim())
                .email(email)
                .status(MerchantStatus.ACTIVE)
                .createdAt(now)
                .updatedAt(now)
                .build();

        try {
            merchantRepository.saveAndFlush(merchant);
        } catch (DataIntegrityViolationException e) {
            // Lost a race with a concurrent onboarding of the same email (unique index).
            throw new MerchantConflictException("A merchant with email " + email + " already exists");
        }

        IssuedCredentialResponse credential = newCredential(merchant.getId(), now);

        log.info("Merchant onboarded. merchantId={}, clientId={}", merchant.getId(), credential.clientId());
        return new CreateMerchantResponse(merchant.getId(), merchant.getName(), merchant.getEmail(),
                merchant.getStatus(), merchant.getCreatedAt(), credential);
    }

    @Override
    @Transactional(readOnly = true)
    public MerchantResponse getMerchant(UUID merchantId) {
        return toResponse(merchantRepository.findById(merchantId)
                .orElseThrow(() -> new MerchantNotFoundException(merchantId)));
    }

    /**
     * Locks the merchant row so concurrent requests can't both pass the limit check. The limit
     * (2 by default) is what allows zero-downtime rotation: issue a new credential, move traffic
     * to it, revoke the old one.
     */
    @Override
    @Transactional
    public IssuedCredentialResponse issueCredential(UUID merchantId) {
        Merchant merchant = merchantRepository.findByIdForUpdate(merchantId)
                .orElseThrow(() -> new MerchantNotFoundException(merchantId));

        if (!merchant.isActive()) {
            throw new MerchantConflictException("Merchant " + merchantId + " is suspended");
        }

        long active = credentialRepository.countByMerchantIdAndStatus(merchantId, CredentialStatus.ACTIVE);
        if (active >= properties.maxActiveCredentials()) {
            throw new MerchantConflictException("Merchant " + merchantId + " already has " + active
                    + " active credentials; revoke one before issuing another");
        }

        IssuedCredentialResponse credential = newCredential(merchantId, LocalDateTime.now());
        log.info("Credential issued. merchantId={}, clientId={}", merchantId, credential.clientId());
        return credential;
    }

    /** Idempotent: revoking an already-revoked credential succeeds. */
    @Override
    @Transactional
    public void revokeCredential(UUID merchantId, String clientId) {
        MerchantCredential credential = credentialRepository.findByClientIdAndMerchantId(clientId, merchantId)
                .orElseThrow(() -> new CredentialNotFoundException(clientId));

        if (credential.isActive()) {
            credential.revoke(LocalDateTime.now());
            log.info("Credential revoked. merchantId={}, clientId={}", merchantId, clientId);
        }
    }

    @Override
    @Transactional
    public MerchantResponse suspend(UUID merchantId) {
        Merchant merchant = merchantRepository.findByIdForUpdate(merchantId)
                .orElseThrow(() -> new MerchantNotFoundException(merchantId));
        if (merchant.isActive()) {
            merchant.suspend(LocalDateTime.now());
            log.info("Merchant suspended. merchantId={}", merchantId);
        }
        return toResponse(merchant);
    }

    @Override
    @Transactional
    public MerchantResponse activate(UUID merchantId) {
        Merchant merchant = merchantRepository.findByIdForUpdate(merchantId)
                .orElseThrow(() -> new MerchantNotFoundException(merchantId));
        if (!merchant.isActive()) {
            merchant.activate(LocalDateTime.now());
            log.info("Merchant activated. merchantId={}", merchantId);
        }
        return toResponse(merchant);
    }

    private IssuedCredentialResponse newCredential(UUID merchantId, LocalDateTime now) {
        String secret = credentialGenerator.newClientSecret();
        MerchantCredential credential = MerchantCredential.builder()
                .id(UUID.randomUUID())
                .merchantId(merchantId)
                .clientId(credentialGenerator.newClientId())
                .clientSecretHash(passwordEncoder.encode(secret))
                .status(CredentialStatus.ACTIVE)
                .createdAt(now)
                .build();
        credentialRepository.save(credential);
        return new IssuedCredentialResponse(credential.getClientId(), secret, credential.getCreatedAt());
    }

    private MerchantResponse toResponse(Merchant merchant) {
        return MerchantResponse.of(merchant, credentialRepository.findByMerchantIdOrderByCreatedAtAsc(merchant.getId())
                .stream()
                .map(CredentialResponse::of)
                .toList());
    }
}
