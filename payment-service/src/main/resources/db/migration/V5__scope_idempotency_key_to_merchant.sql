-- Idempotency keys are chosen by the client, so two merchants can legitimately pick the
-- same key. Uniqueness is per merchant, not global.
ALTER TABLE payment
    DROP CONSTRAINT uk_payment_idempotency_key;

ALTER TABLE payment
    ADD CONSTRAINT uk_payment_merchant_idempotency_key
        UNIQUE (merchant_id, idempotency_key);

-- SHA-256 of the canonical create request, used to reject a key reused with a different
-- body. Nullable: rows created before this migration have no fingerprint and are replayed
-- without the check.
ALTER TABLE payment
    ADD COLUMN request_hash VARCHAR(64);
