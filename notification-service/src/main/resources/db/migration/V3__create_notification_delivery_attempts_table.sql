CREATE TABLE notification_delivery_attempts
(
    id UUID PRIMARY KEY,

    notification_id UUID NOT NULL REFERENCES notifications (id),

    attempt_number INT NOT NULL,
    status VARCHAR(20) NOT NULL,
    error_message VARCHAR(1000),

    attempted_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_notification_delivery_attempts_notification_id
    ON notification_delivery_attempts (notification_id);
