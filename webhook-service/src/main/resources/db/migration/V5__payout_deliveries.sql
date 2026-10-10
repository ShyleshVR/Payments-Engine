-- Payout webhooks: a delivery is about a payment or about a payout, never both.
ALTER TABLE webhook_deliveries
    ALTER COLUMN payment_id DROP NOT NULL;

ALTER TABLE webhook_deliveries
    ADD COLUMN payout_id UUID;

ALTER TABLE webhook_deliveries
    ADD CONSTRAINT ck_webhook_deliveries_subject CHECK ((payment_id IS NULL) <> (payout_id IS NULL));

CREATE INDEX idx_webhook_deliveries_payout_id
    ON webhook_deliveries (payout_id)
    WHERE payout_id IS NOT NULL;
