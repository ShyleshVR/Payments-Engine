CREATE TABLE ledger_transactions
(
    id UUID PRIMARY KEY,

    event_id UUID NOT NULL,
    payment_id UUID NOT NULL,
    type VARCHAR(30) NOT NULL,

    created_at TIMESTAMP NOT NULL,

    CONSTRAINT uq_ledger_transactions_event UNIQUE (event_id)
);

CREATE INDEX idx_ledger_transactions_payment_id
    ON ledger_transactions (payment_id);

CREATE TABLE ledger_entries
(
    id UUID PRIMARY KEY,

    transaction_id UUID NOT NULL REFERENCES ledger_transactions (id),
    account_id UUID NOT NULL REFERENCES ledger_accounts (id),

    direction VARCHAR(10) NOT NULL,
    amount NUMERIC(19, 4) NOT NULL,
    currency VARCHAR(3) NOT NULL,

    created_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_ledger_entries_account_id
    ON ledger_entries (account_id);

CREATE INDEX idx_ledger_entries_transaction_id
    ON ledger_entries (transaction_id);
