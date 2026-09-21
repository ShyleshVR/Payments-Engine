CREATE TABLE notifications
(
    id UUID PRIMARY KEY,

    event_id UUID NOT NULL,
    event_type VARCHAR(100) NOT NULL,

    payment_id UUID NOT NULL,
    customer_id UUID,

    channel VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,

    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP,
    last_error VARCHAR(1000),

    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,

    CONSTRAINT uq_notifications_event_channel UNIQUE (event_id, channel)
);

CREATE INDEX idx_notifications_status_next_attempt_at
    ON notifications (status, next_attempt_at);

CREATE INDEX idx_notifications_payment_id
    ON notifications (payment_id);
