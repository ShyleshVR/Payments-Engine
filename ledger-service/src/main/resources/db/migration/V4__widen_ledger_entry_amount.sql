-- payment.amount is NUMERIC(19,2): up to 17 integer digits. ledger_entries.amount was
-- NUMERIC(19,4): only 15. A completed payment of 10^15 or more failed to post with a numeric
-- overflow and the ledger silently diverged from payments. NUMERIC(21,4) holds every amount
-- payment-service accepts, keeping 4 decimal places for future FX/fee splits.
ALTER TABLE ledger_entries
    ALTER COLUMN amount TYPE NUMERIC(21, 4);
