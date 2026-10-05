package com.shylesh.payment_service.service.impl;

import com.shylesh.payment_service.service.IdempotencyService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;


@Service
@RequiredArgsConstructor
@Slf4j
public class RedisIdempotencyService implements IdempotencyService {

    private static final String KEY_PREFIX = "idempotency:payment:";

    private final RedisTemplate<String, String> redisTemplate;

    @Override
    public Optional<String> get(UUID merchantId, String key) {
        try {
            return Optional.ofNullable(redisTemplate.opsForValue().get(cacheKey(merchantId, key)));
        } catch (DataAccessException e) {
            log.warn("Idempotency cache read failed, falling back to database: {}", e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void put(UUID merchantId, String key, String paymentId) {
        try {
            redisTemplate.opsForValue().set(
                    cacheKey(merchantId, key),
                    paymentId,
                    Duration.ofHours(24)
            );
        } catch (DataAccessException e) {
            log.warn("Idempotency cache write failed, database remains authoritative: {}", e.getMessage());
        }
    }

    private String cacheKey(UUID merchantId, String key) {
        return KEY_PREFIX + merchantId + ":" + key;
    }
}
