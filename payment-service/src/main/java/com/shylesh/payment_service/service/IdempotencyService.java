package com.shylesh.payment_service.service;

import java.util.Optional;
import java.util.UUID;

/**
 * Read-through cache of (merchantId, Idempotency-Key) -> paymentId. The payment table's
 * unique (merchant_id, idempotency_key) constraint is the source of truth; this cache only
 * saves a DB lookup on replays, so implementations must treat it as best-effort.
 */
public interface IdempotencyService {

    Optional<String> get(UUID merchantId, String key);

    void put(UUID merchantId, String key, String paymentId);

}
