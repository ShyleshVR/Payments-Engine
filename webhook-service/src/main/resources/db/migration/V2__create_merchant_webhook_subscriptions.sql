CREATE TABLE merchant_webhook_subscriptions
(
    id UUID PRIMARY KEY,

    merchant_id UUID NOT NULL,
    url VARCHAR(2048) NOT NULL,
    -- Stored as-is because HMAC signing needs the raw value; it is only ever returned
    -- to the caller once, in the create response.
    secret VARCHAR(128) NOT NULL,
    active BOOLEAN NOT NULL,

    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

-- V1: one active subscription per merchant. Deactivated rows are kept for audit, so
-- the constraint only applies to active ones.
CREATE UNIQUE INDEX uq_merchant_webhook_subscriptions_active_merchant
    ON merchant_webhook_subscriptions (merchant_id)
    WHERE active;
