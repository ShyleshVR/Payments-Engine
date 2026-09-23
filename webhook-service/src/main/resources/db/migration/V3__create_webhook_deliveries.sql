CREATE TABLE webhook_deliveries
(
    id UUID PRIMARY KEY,

    event_id UUID NOT NULL,
    event_type VARCHAR(100) NOT NULL,

    payment_id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    subscription_id UUID NOT NULL REFERENCES merchant_webhook_subscriptions (id),

    url VARCHAR(2048) NOT NULL,
    -- Rendered once at creation so every retry sends byte-identical content.
    payload TEXT NOT NULL,

    status VARCHAR(20) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP,
    last_error VARCHAR(1000),

    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,

    CONSTRAINT uq_webhook_deliveries_event_merchant UNIQUE (event_id, merchant_id)
);

CREATE INDEX idx_webhook_deliveries_status_next_attempt_at
    ON webhook_deliveries (status, next_attempt_at);

CREATE INDEX idx_webhook_deliveries_payment_id
    ON webhook_deliveries (payment_id);
