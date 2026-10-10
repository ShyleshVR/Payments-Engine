-- Payouts: bank transfers to merchants' accounts. Asynchronous like real bank rails: a transfer
-- is accepted as PENDING and settles (PAID) or fails later; a paid transfer can still be
-- RETURNED by the receiving bank afterwards.
CREATE TABLE transfers
(
    id                 VARCHAR(40) PRIMARY KEY,
    bank_account       VARCHAR(64)    NOT NULL,
    amount             NUMERIC(19, 4) NOT NULL,
    currency           VARCHAR(3)     NOT NULL,
    reference          VARCHAR(100),
    status             VARCHAR(20)    NOT NULL,
    failure_code       VARCHAR(64),
    created_at         TIMESTAMP      NOT NULL,
    updated_at         TIMESTAMP      NOT NULL,
    paid_at            TIMESTAMP,
    failed_at          TIMESTAMP,
    returned_at        TIMESTAMP,
    -- when the bank next moves the transfer on (settle, fail or return); NULL: final
    next_transition_at TIMESTAMP
);

CREATE INDEX idx_transfers_due
    ON transfers (next_transition_at)
    WHERE next_transition_at IS NOT NULL;

-- Settlement report: transfers created, paid, failed or returned in a period.
CREATE INDEX idx_transfers_created_at ON transfers (created_at);
CREATE INDEX idx_transfers_paid_at ON transfers (paid_at) WHERE paid_at IS NOT NULL;
CREATE INDEX idx_transfers_failed_at ON transfers (failed_at) WHERE failed_at IS NOT NULL;
CREATE INDEX idx_transfers_returned_at ON transfers (returned_at) WHERE returned_at IS NOT NULL;
