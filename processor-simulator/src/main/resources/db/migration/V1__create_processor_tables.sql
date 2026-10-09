-- A card authorization: funds reserved on the card, later captured (charged) or voided (released).
CREATE TABLE authorizations
(
    id              VARCHAR(40) PRIMARY KEY,
    payment_method  VARCHAR(64)    NOT NULL,
    amount          NUMERIC(19, 4) NOT NULL,
    currency        VARCHAR(3)     NOT NULL,
    status          VARCHAR(20)    NOT NULL,
    captured_amount NUMERIC(19, 4) NOT NULL DEFAULT 0,
    refunded_amount NUMERIC(19, 4) NOT NULL DEFAULT 0,
    reference       VARCHAR(100),
    created_at      TIMESTAMP      NOT NULL,
    updated_at      TIMESTAMP      NOT NULL
);

CREATE TABLE refunds
(
    id               VARCHAR(40) PRIMARY KEY,
    authorization_id VARCHAR(40)    NOT NULL REFERENCES authorizations (id),
    amount           NUMERIC(19, 4) NOT NULL,
    created_at       TIMESTAMP      NOT NULL
);

-- One row per Idempotency-Key. The first request inserts it and holds the row lock until it has
-- stored its response, so a concurrent duplicate waits and then replays that response.
-- A REVERSAL of a key never seen inserts a row with reversed = true and no response: a late
-- authorization arriving with that key is then rejected instead of reserving funds.
CREATE TABLE operations
(
    idempotency_key  VARCHAR(100) PRIMARY KEY,
    operation_type   VARCHAR(20) NOT NULL,
    request_hash     VARCHAR(64),
    reversed         BOOLEAN     NOT NULL DEFAULT FALSE,
    response_status  INTEGER,
    response_body    TEXT,
    authorization_id VARCHAR(40),
    created_at       TIMESTAMP   NOT NULL,
    completed_at     TIMESTAMP
);

-- Attempts per Idempotency-Key, for test payment methods that fail their first calls.
CREATE TABLE operation_attempts
(
    idempotency_key VARCHAR(100) PRIMARY KEY,
    attempts        INTEGER NOT NULL
);

-- Simulator switches shared by all replicas (a single row).
CREATE TABLE simulator_state
(
    id           INTEGER PRIMARY KEY CHECK (id = 1),
    outage_until TIMESTAMP
);
INSERT INTO simulator_state (id, outage_until) VALUES (1, NULL);
