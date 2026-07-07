package com.shylesh.payment_service.common.identifier;

import org.springframework.stereotype.Service;

import com.shylesh.payment_service.exception.InvalidIdentifierException;

import java.util.UUID;

@Service
public class IdentifierServiceImpl implements IdentifierService {

    private static final String PAYMENT_PREFIX = "pay_";

    @Override
    public String paymentId(UUID id) {
        return PAYMENT_PREFIX + id;
    }

    @Override
    public UUID parsePaymentId(String paymentId) {

        if (paymentId == null || !paymentId.startsWith(PAYMENT_PREFIX)) {
            throw new InvalidIdentifierException(paymentId);
        }

        try {
            return UUID.fromString(paymentId.substring(PAYMENT_PREFIX.length()));
        } catch (IllegalArgumentException ex) {
            throw new InvalidIdentifierException(paymentId);
        }
    }
}