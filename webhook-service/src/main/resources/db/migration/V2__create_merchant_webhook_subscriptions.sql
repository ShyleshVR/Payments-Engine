CREATE TABLE merchant_webhook_subscriptions
(
    id UUID PRIMARY KEY,

    merchant_id UUID NOT NULL,
    url VARCHAR(2048) NOT NULL,

    -- HMAC signing key. Stored in plain text until secret rotation/encryption is built
    -- (deferred: see the webhook-service plan).
    secret VARCHAR(128) NOT NULL,

    active BOOLEAN NOT NULL,

    created_at TIMESTAMP NOT NULL,
    deactivated_at TIMESTAMP
);

-- V1: one active subscription per merchant. Deactivated rows are kept for the audit trail
-- (deliveries reference them), so uniqueness only applies to active ones.
CREATE UNIQUE INDEX uq_webhook_subscriptions_active_merchant
    ON merchant_webhook_subscriptions (merchant_id)
    WHERE active;
