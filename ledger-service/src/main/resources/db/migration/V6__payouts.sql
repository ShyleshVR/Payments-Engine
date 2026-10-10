-- Merchant payouts: payout-service's saga holds the payable balance, the bank pays it out, and the
-- ledger finalizes (or releases, or books a return). A posting now belongs to a payment or to a
-- payout, never both.

ALTER TABLE ledger_transactions
    ALTER COLUMN payment_id DROP NOT NULL;

ALTER TABLE ledger_transactions
    ADD COLUMN payout_id UUID;

ALTER TABLE ledger_transactions
    ADD CONSTRAINT ck_ledger_transactions_subject CHECK ((payment_id IS NULL) <> (payout_id IS NULL));

CREATE INDEX idx_ledger_transactions_payout_id
    ON ledger_transactions (payout_id)
    WHERE payout_id IS NOT NULL;

ALTER TABLE processed_commands
    ALTER COLUMN payment_id DROP NOT NULL;

ALTER TABLE processed_commands
    ADD COLUMN payout_id UUID;

ALTER TABLE processed_commands
    ADD CONSTRAINT ck_processed_commands_subject CHECK ((payment_id IS NULL) <> (payout_id IS NULL));

-- Payable balances: settlement credits since the cutoff are found by account, type and time.
CREATE INDEX idx_ledger_entries_account_created
    ON ledger_entries (account_id, created_at);
