-- A day is no longer held in memory. A run first copies the day's records from the processor and
-- the ledger into this table, a page at a time, then reads them back a few thousand payments (or
-- payouts) at a time. One row per record, the record itself as JSON. payment_id or payout_id says
-- whose it is; seq keeps the order the source listed them in (the rules take "the first" capture
-- or settlement of a payment). Rows live only while their run is RUNNING.
--
-- UNLOGGED: the rows are disposable, so they skip the write-ahead log (several times faster to
-- fill). After a Postgres crash the table comes back empty; the interrupted run is retried anyway.
CREATE UNLOGGED TABLE reconciliation_staging
(
    seq        BIGINT GENERATED ALWAYS AS IDENTITY,
    run_id     UUID        NOT NULL,
    kind       VARCHAR(20) NOT NULL,
    payment_id UUID,
    payout_id  UUID,
    payload    TEXT        NOT NULL
);

-- "the next payments of this run, in id order" and "everything staged for these payments"
CREATE INDEX idx_reconciliation_staging_payment ON reconciliation_staging (run_id, payment_id)
    WHERE payment_id IS NOT NULL;

CREATE INDEX idx_reconciliation_staging_payout ON reconciliation_staging (run_id, payout_id)
    WHERE payout_id IS NOT NULL;

-- A run says it is alive while it works. A RUNNING run that stopped saying so (its pod was killed)
-- is taken over by the next attempt instead of blocking its day for ever.
ALTER TABLE reconciliation_run
    ADD COLUMN heartbeat_at TIMESTAMP;
