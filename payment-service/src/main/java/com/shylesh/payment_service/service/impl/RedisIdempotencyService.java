package com.shylesh.payment_service.service.impl;

import com.shylesh.payment_service.service.IdempotencyService;

import lombok.RequiredArgsConstructor;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;


@Service
@RequiredArgsConstructor
public class RedisIdempotencyService implements IdempotencyService {

    private final RedisTemplate<String, String> redisTemplate;

    @Override
    public Optional<String> get(String key) {
        return Optional.ofNullable(redisTemplate.opsForValue().get(key));
    }

    @Override
    public void put(String key, String paymentId) {
        redisTemplate.opsForValue().set(
                key,
                paymentId,
                Duration.ofHours(24)
        );
    }
}