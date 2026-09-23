CREATE TABLE webhook_delivery_attempts
(
    id UUID PRIMARY KEY,

    delivery_id UUID NOT NULL REFERENCES webhook_deliveries (id),

    attempt_number INT NOT NULL,
    status VARCHAR(20) NOT NULL,
    response_code INT,
    error_message VARCHAR(1000),

    attempted_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_webhook_delivery_attempts_delivery_id
    ON webhook_delivery_attempts (delivery_id);
