package com.shylesh.merchant_service.persistence;

public enum MerchantStatus {

    ACTIVE,
    /** Can't obtain new tokens. Tokens already issued expire within the access token TTL. */
    SUSPENDED

}
