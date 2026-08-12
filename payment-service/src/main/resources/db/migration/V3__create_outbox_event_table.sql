CREATE TABLE outbox_event
(
    id UUID PRIMARY KEY,

    aggregate_id UUID NOT NULL,

    aggregate_type VARCHAR(100) NOT NULL,

    event_type VARCHAR(100) NOT NULL,

    payload TEXT NOT NULL,

    status VARCHAR(20) NOT NULL,

    created_at TIMESTAMP NOT NULL,

    published_at TIMESTAMP
);

CREATE INDEX idx_outbox_event_status_created_at
    ON outbox_event (status, created_at);