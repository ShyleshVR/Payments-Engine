CREATE TABLE ledger_accounts
(
    id UUID PRIMARY KEY,

    owner_type VARCHAR(30) NOT NULL,
    owner_id UUID,
    currency VARCHAR(3) NOT NULL,

    created_at TIMESTAMP NOT NULL,

    CONSTRAINT uq_ledger_accounts_owner UNIQUE (owner_type, owner_id, currency)
);

-- Postgres treats NULL as distinct from NULL, so the table-level unique constraint above
-- does not stop duplicate platform-wide accounts (owner_id IS NULL). A partial index covers
-- that case separately.
CREATE UNIQUE INDEX uq_ledger_accounts_owner_platform
    ON ledger_accounts (owner_type, currency)
    WHERE owner_id IS NULL;
