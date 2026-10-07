CREATE TABLE webhook_deliveries
(
    id UUID PRIMARY KEY,

    event_id UUID NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payment_id UUID NOT NULL,
    merchant_id UUID NOT NULL,

    subscription_id UUID NOT NULL REFERENCES merchant_webhook_subscriptions (id),
    url VARCHAR(2048) NOT NULL,

    -- Exact JSON body sent to the merchant, rendered once so every retry carries identical
    -- bytes (only the timestamp and signature headers change per attempt).
    payload TEXT NOT NULL,

    status VARCHAR(20) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP,
    last_error VARCHAR(1000),

    -- Set once the dead letter for a FAILED delivery has been acknowledged by Kafka.
    dlt_published_at TIMESTAMP,

    version BIGINT NOT NULL DEFAULT 0,

    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,

    CONSTRAINT uq_webhook_deliveries_event_merchant UNIQUE (event_id, merchant_id)
);

CREATE INDEX idx_webhook_deliveries_status_next_attempt_at
    ON webhook_deliveries (status, next_attempt_at);

CREATE INDEX idx_webhook_deliveries_payment_id
    ON webhook_deliveries (payment_id);

CREATE INDEX idx_webhook_deliveries_open_by_subscription
    ON webhook_deliveries (subscription_id)
    WHERE status IN ('PENDING', 'RETRYING');

CREATE INDEX idx_webhook_deliveries_dlt_pending
    ON webhook_deliveries (updated_at)
    WHERE status = 'FAILED' AND dlt_published_at IS NULL;
