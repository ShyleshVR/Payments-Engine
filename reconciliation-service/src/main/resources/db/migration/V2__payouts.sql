-- Payouts are reconciled too: the bank's transfers, the ledger's payout postings and payout-service.
ALTER TABLE reconciliation_run
    ADD COLUMN payouts_checked INT;

ALTER TABLE reconciliation_run
    ADD COLUMN payouts_matched INT;

ALTER TABLE reconciliation_run
    ADD COLUMN payouts_pending INT;

-- A discrepancy is about a payment (payment_id) or a payout (payout_id).
ALTER TABLE reconciliation_discrepancy
    ADD COLUMN payout_id UUID;
