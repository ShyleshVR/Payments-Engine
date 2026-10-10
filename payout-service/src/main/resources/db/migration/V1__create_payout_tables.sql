-- Where a merchant's payouts go: a bank account token (one per merchant).
CREATE TABLE payout_destination
(
    merchant_id  UUID PRIMARY KEY,
    bank_account VARCHAR(64) NOT NULL,
    created_at   TIMESTAMP   NOT NULL,
    updated_at   TIMESTAMP   NOT NULL
);

-- One payout of a merchant's payable balance to their bank account.
CREATE TABLE payout
(
    id              UUID PRIMARY KEY,
    merchant_id     UUID           NOT NULL,
    amount          NUMERIC(19, 4) NOT NULL,
    currency        VARCHAR(3)     NOT NULL,
    -- the destination when the payout was created (later changes don't redirect it)
    bank_account    VARCHAR(64)    NOT NULL,
    status          VARCHAR(20)    NOT NULL,
    failure_code    VARCHAR(64),
    trigger_type    VARCHAR(20)    NOT NULL,
    -- BATCH: the day's batch it belongs to
    batch_date      DATE,
    -- INSTANT: the merchant's Idempotency-Key and a hash of the request it came with
    idempotency_key VARCHAR(100),
    request_hash    VARCHAR(64),
    -- only money settled before this time may be paid out (sent with the ledger hold)
    cutoff          TIMESTAMP      NOT NULL,
    transfer_id     VARCHAR(40),
    version         BIGINT         NOT NULL,
    created_at      TIMESTAMP      NOT NULL,
    updated_at      TIMESTAMP      NOT NULL,
    paid_at         TIMESTAMP,
    failed_at       TIMESTAMP,
    returned_at     TIMESTAMP
);

-- The batch pays each merchant and currency at most once a day, however often it runs.
CREATE UNIQUE INDEX uq_payout_batch
    ON payout (merchant_id, currency, batch_date)
    WHERE trigger_type = 'BATCH';

-- An instant payout request retried with the same key is the same payout.
CREATE UNIQUE INDEX uq_payout_idempotency_key
    ON payout (merchant_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE INDEX idx_payout_merchant_created
    ON payout (merchant_id, created_at DESC);

-- One run of the daily batch.
CREATE TABLE payout_batch
(
    batch_date             DATE PRIMARY KEY,
    status                 VARCHAR(20) NOT NULL,
    cutoff                 TIMESTAMP   NOT NULL,
    payouts_created        INT,
    skipped_no_destination INT,
    error                  VARCHAR(1000),
    started_at             TIMESTAMP   NOT NULL,
    finished_at            TIMESTAMP
);

-- The payout saga: hold in the ledger -> transfer at the bank -> wait for it -> finalize in the
-- ledger -> watch for a return. Same shape as payment-service's payment_saga.
CREATE TABLE payout_saga
(
    id                 UUID PRIMARY KEY,
    payout_id          UUID         NOT NULL REFERENCES payout (id),
    state              VARCHAR(40)  NOT NULL,
    version            BIGINT       NOT NULL,
    attempt            INT          NOT NULL DEFAULT 0,
    next_attempt_at    TIMESTAMP,
    step_started_at    TIMESTAMP    NOT NULL,
    pending_command_id UUID,
    failure_code       VARCHAR(64),
    stuck_state        VARCHAR(40),
    last_error         VARCHAR(1000),
    trace_parent       VARCHAR(100),
    created_at         TIMESTAMP    NOT NULL,
    updated_at         TIMESTAMP    NOT NULL,
    finished_at        TIMESTAMP
);

CREATE UNIQUE INDEX uq_payout_saga_payout
    ON payout_saga (payout_id);

CREATE INDEX idx_payout_saga_due
    ON payout_saga (next_attempt_at)
    WHERE finished_at IS NULL AND next_attempt_at IS NOT NULL;

CREATE TABLE payout_saga_step
(
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    saga_id     UUID          NOT NULL REFERENCES payout_saga (id),
    state       VARCHAR(40)   NOT NULL,
    outcome     VARCHAR(40)   NOT NULL,
    detail      VARCHAR(1000),
    occurred_at TIMESTAMP     NOT NULL
);

CREATE INDEX idx_payout_saga_step_saga
    ON payout_saga_step (saga_id, id);

-- Ledger commands and payout events, written with the change that caused them (transactional
-- outbox) and relayed to Kafka by OutboxRelay.
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

-- ShedLock: one replica at a time runs the daily batch.
CREATE TABLE shedlock
(
    name       VARCHAR(64)  NOT NULL PRIMARY KEY,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);
