package com.shylesh.payment_service.common.identifier;

import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class IdentifierServiceImpl implements IdentifierService {

    private static final String PAYMENT_PREFIX = "pay_";

    @Override
    public String paymentId(UUID id) {
        return PAYMENT_PREFIX + id;
    }
}