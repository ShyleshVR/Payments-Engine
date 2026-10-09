-- One reconciliation of one business day (UTC).
CREATE TABLE reconciliation_run
(
    id                UUID PRIMARY KEY,
    business_date     DATE          NOT NULL,
    trigger_type      VARCHAR(20)   NOT NULL,
    status            VARCHAR(20)   NOT NULL,
    started_at        TIMESTAMP     NOT NULL,
    finished_at       TIMESTAMP,
    payments_checked  INT,
    matched           INT,
    pending           INT,
    discrepancy_count INT,
    error             VARCHAR(1000)
);

CREATE INDEX idx_reconciliation_run_date ON reconciliation_run (business_date, status);

-- A day is reconciled by one run at a time (scheduled and manual runs alike).
CREATE UNIQUE INDEX uq_reconciliation_run_in_progress
    ON reconciliation_run (business_date)
    WHERE status = 'RUNNING';

CREATE TABLE reconciliation_discrepancy
(
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_id           UUID          NOT NULL REFERENCES reconciliation_run (id),
    type             VARCHAR(40)   NOT NULL,
    payment_id       UUID,
    processor_amount NUMERIC(19, 4),
    ledger_amount    NUMERIC(19, 4),
    payment_status   VARCHAR(30),
    detail           VARCHAR(1000)
);

CREATE INDEX idx_reconciliation_discrepancy_run ON reconciliation_discrepancy (run_id);

-- ShedLock: the daily job runs on one replica only.
CREATE TABLE shedlock
(
    name       VARCHAR(64)  PRIMARY KEY,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);
