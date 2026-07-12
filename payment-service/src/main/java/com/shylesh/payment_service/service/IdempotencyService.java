package com.shylesh.payment_service.service;

import org.springframework.stereotype.Service;
import java.util.Optional;

@Service
public interface IdempotencyService {

    Optional<String> get(String key);

    void put(String key, String paymentId);

}
