package com.shylesh.payment_service.common.identifier;

import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class UuidGenerator implements IdGenerator {

    @Override
    public UUID generate() {
        return UUID.randomUUID();
    }
}