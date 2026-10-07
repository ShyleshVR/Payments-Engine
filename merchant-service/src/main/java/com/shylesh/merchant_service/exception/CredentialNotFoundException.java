package com.shylesh.merchant_service.exception;

public class CredentialNotFoundException extends RuntimeException {

    public CredentialNotFoundException(String clientId) {
        super("Credential not found: " + clientId);
    }
}
