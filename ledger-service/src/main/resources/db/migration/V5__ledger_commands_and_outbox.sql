-- The ledger now posts only on commands from payment-service's saga orchestrator.

-- One row per command handled, with the reply it produced: a duplicate command (redelivery, or
-- the orchestrator re-sending after a timeout) posts nothing and gets the same reply again.
CREATE TABLE processed_commands
(
    command_id   UUID PRIMARY KEY,
    command_type VARCHAR(40) NOT NULL,
    payment_id   UUID        NOT NULL,
    reply        TEXT        NOT NULL,
    processed_at TIMESTAMP   NOT NULL
);

-- Which saga a posting belongs to: a refund's hold, release and final posting are tied together
-- by it (a payment can have several refund attempts over time).
ALTER TABLE ledger_transactions
    ADD COLUMN saga_id UUID;

CREATE INDEX idx_ledger_transactions_saga_id
    ON ledger_transactions (saga_id)
    WHERE saga_id IS NOT NULL;

COMMENT ON COLUMN ledger_transactions.event_id IS 'The event or command that caused this transaction (unique: posted once)';

-- Replies to the orchestrator, written in the same transaction as the posting they report
-- (transactional outbox) and relayed to Kafka by OutboxRelay. Same layout and ordering rules as
-- payment-service's outbox.
CREATE TABLE outbox_event
(
    id              UUID PRIMARY KEY,
    aggregate_id    UUID          NOT NULL,
    topic           VARCHAR(100)  NOT NULL,
    event_type      VARCHAR(100)  NOT NULL,
    payload         TEXT          NOT NULL,
    status          VARCHAR(20)   NOT NULL,
    created_at      TIMESTAMP     NOT NULL,
    published_at    TIMESTAMP,
    seq             BIGINT GENERATED ALWAYS AS IDENTITY,
    attempt_count   INT           NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP,
    last_error      VARCHAR(1000),
    -- W3C traceparent of the command being handled; the relay continues that trace
    trace_parent    VARCHAR(100)
);

CREATE INDEX idx_outbox_event_pending_seq
    ON outbox_event (seq)
    WHERE status = 'PENDING';

CREATE INDEX idx_outbox_event_aggregate_seq
    ON outbox_event (aggregate_id, seq)
    WHERE status <> 'PUBLISHED';

CREATE INDEX idx_outbox_event_published_at
    ON outbox_event (published_at)
    WHERE status = 'PUBLISHED';
