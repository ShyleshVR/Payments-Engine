package com.shylesh.merchant_service.service;

import com.shylesh.merchant_service.dto.CreateMerchantRequest;
import com.shylesh.merchant_service.dto.CreateMerchantResponse;
import com.shylesh.merchant_service.dto.IssuedCredentialResponse;
import com.shylesh.merchant_service.dto.MerchantResponse;

import java.util.UUID;

public interface MerchantAdminService {

    CreateMerchantResponse createMerchant(CreateMerchantRequest request);

    MerchantResponse getMerchant(UUID merchantId);

    IssuedCredentialResponse issueCredential(UUID merchantId);

    void revokeCredential(UUID merchantId, String clientId);

    MerchantResponse suspend(UUID merchantId);

    MerchantResponse activate(UUID merchantId);
}
