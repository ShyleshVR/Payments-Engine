-- The reconciliation lists a period's transactions oldest first, a page at a time
-- (GET /api/v1/ledger/transactions). Without an index on created_at every page scanned and sorted
-- the table. With (created_at, id), a page is a range scan that starts at the previous page's
-- last row, and costs the same however far into the period it is.
CREATE INDEX idx_ledger_transactions_created_at ON ledger_transactions (created_at, id);
