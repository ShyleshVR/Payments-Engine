-- Payments are now driven by sagas: authorize -> capture -> settle, and refunds (hold -> refund
-- at the processor -> finalize), with compensation when a step fails.

ALTER TABLE payment
    ADD COLUMN payment_method VARCHAR(64);

ALTER TABLE payment
    ADD COLUMN capture_method VARCHAR(20) NOT NULL DEFAULT 'AUTOMATIC';

-- Why the payment failed or was cancelled (processor decline code, PROCESSOR_UNAVAILABLE, ...).
ALTER TABLE payment
    ADD COLUMN failure_code VARCHAR(64);

-- Why the last refund attempt failed (the payment itself stays SUCCESS).
ALTER TABLE payment
    ADD COLUMN refund_failure_code VARCHAR(64);

ALTER TABLE payment
    ADD COLUMN processor_authorization_id VARCHAR(40);

-- MANUAL capture: when an uncaptured authorization is voided automatically.
ALTER TABLE payment
    ADD COLUMN authorization_expires_at TIMESTAMP;

CREATE TABLE payment_saga
(
    id                 UUID PRIMARY KEY,
    payment_id         UUID          NOT NULL REFERENCES payment (id),
    type               VARCHAR(20)   NOT NULL,
    state              VARCHAR(40)   NOT NULL,
    version            BIGINT        NOT NULL,
    -- attempts of the current step (processor calls, or command sends while awaiting a reply)
    attempt            INT           NOT NULL DEFAULT 0,
    -- when the worker next acts on this saga (retry, reply timeout, authorization expiry);
    -- while a step runs it is pushed forward as a lease; NULL: waiting for a reply or the merchant
    next_attempt_at    TIMESTAMP,
    step_started_at    TIMESTAMP     NOT NULL,
    -- the ledger command whose reply the saga is waiting for
    pending_command_id UUID,
    -- for VOIDING: the payment status once the authorization is released (FAILED or CANCELLED)
    resolution         VARCHAR(20),
    failure_code       VARCHAR(64),
    -- REQUIRES_ATTENTION: the state an operator retry resumes
    stuck_state        VARCHAR(40),
    last_error         VARCHAR(1000),
    -- W3C traceparent of the request that started the saga, so every step joins its trace
    trace_parent       VARCHAR(100),
    created_at         TIMESTAMP     NOT NULL,
    updated_at         TIMESTAMP     NOT NULL,
    finished_at        TIMESTAMP
);

-- At most one active saga per payment (a refund can only start once the payment saga ended).
CREATE UNIQUE INDEX uq_payment_saga_active
    ON payment_saga (payment_id)
    WHERE finished_at IS NULL;

-- Worker poll: active sagas that are due.
CREATE INDEX idx_payment_saga_due
    ON payment_saga (next_attempt_at)
    WHERE finished_at IS NULL AND next_attempt_at IS NOT NULL;

CREATE INDEX idx_payment_saga_payment
    ON payment_saga (payment_id, created_at);

-- Audit trail: every step outcome of every saga.
CREATE TABLE payment_saga_step
(
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    saga_id     UUID          NOT NULL REFERENCES payment_saga (id),
    state       VARCHAR(40)   NOT NULL,
    outcome     VARCHAR(40)   NOT NULL,
    detail      VARCHAR(1000),
    occurred_at TIMESTAMP     NOT NULL
);

CREATE INDEX idx_payment_saga_step_saga
    ON payment_saga_step (saga_id, id);

-- The outbox now also carries saga commands to the ledger.
ALTER TABLE outbox_event
    ADD COLUMN topic VARCHAR(100) NOT NULL DEFAULT 'payment-created';

ALTER TABLE outbox_event
    ADD COLUMN trace_parent VARCHAR(100);
