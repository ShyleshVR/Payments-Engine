package com.shylesh.merchant_service.controller;

import com.shylesh.merchant_service.dto.CreateMerchantRequest;
import com.shylesh.merchant_service.dto.CreateMerchantResponse;
import com.shylesh.merchant_service.dto.IssuedCredentialResponse;
import com.shylesh.merchant_service.dto.MerchantResponse;
import com.shylesh.merchant_service.service.MerchantAdminService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** Operator API for onboarding merchants and managing their credentials. */
@RestController
@RequestMapping("/api/v1/merchants")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('SCOPE_merchants:admin')")
public class MerchantAdminController {

    private final MerchantAdminService merchantAdminService;

    @PostMapping
    public ResponseEntity<CreateMerchantResponse> create(@Valid @RequestBody CreateMerchantRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(merchantAdminService.createMerchant(request));
    }

    @GetMapping("/{merchantId}")
    public MerchantResponse get(@PathVariable UUID merchantId) {
        return merchantAdminService.getMerchant(merchantId);
    }

    @PostMapping("/{merchantId}/credentials")
    public ResponseEntity<IssuedCredentialResponse> issueCredential(@PathVariable UUID merchantId) {
        return ResponseEntity.status(HttpStatus.CREATED).body(merchantAdminService.issueCredential(merchantId));
    }

    @DeleteMapping("/{merchantId}/credentials/{clientId}")
    public ResponseEntity<Void> revokeCredential(@PathVariable UUID merchantId, @PathVariable String clientId) {
        merchantAdminService.revokeCredential(merchantId, clientId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{merchantId}/suspend")
    public MerchantResponse suspend(@PathVariable UUID merchantId) {
        return merchantAdminService.suspend(merchantId);
    }

    @PostMapping("/{merchantId}/activate")
    public MerchantResponse activate(@PathVariable UUID merchantId) {
        return merchantAdminService.activate(merchantId);
    }
}
