-- Strict insertion order. created_at can tie, and ordering per aggregate must be exact:
-- a PAYMENT_REFUNDED must never reach Kafka before the PAYMENT_COMPLETED it follows.
ALTER TABLE outbox_event
    ADD COLUMN seq BIGINT GENERATED ALWAYS AS IDENTITY;

ALTER TABLE outbox_event
    ADD COLUMN attempt_count INT NOT NULL DEFAULT 0;

ALTER TABLE outbox_event
    ADD COLUMN next_attempt_at TIMESTAMP;

ALTER TABLE outbox_event
    ADD COLUMN last_error VARCHAR(1000);

-- Claim query: oldest publishable PENDING event.
CREATE INDEX idx_outbox_event_pending_seq
    ON outbox_event (seq)
    WHERE status = 'PENDING';

-- Ordering guard: "is there an earlier unpublished event for this aggregate?"
CREATE INDEX idx_outbox_event_aggregate_seq
    ON outbox_event (aggregate_id, seq)
    WHERE status <> 'PUBLISHED';

-- Cleanup job: published events past retention.
CREATE INDEX idx_outbox_event_published_at
    ON outbox_event (published_at)
    WHERE status = 'PUBLISHED';
