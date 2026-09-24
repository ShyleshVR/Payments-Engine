-- A FAILED delivery with dlt_published_at IS NULL is a dead letter that has not yet been
-- confirmed by Kafka. WebhookDeadLetterRelay publishes these and stamps the column only
-- after Kafka acknowledges, so a Kafka outage can delay a dead letter but never lose it.
ALTER TABLE webhook_deliveries
    ADD COLUMN dlt_published_at TIMESTAMP;

CREATE INDEX idx_webhook_deliveries_dlt_pending
    ON webhook_deliveries (updated_at)
    WHERE status = 'FAILED' AND dlt_published_at IS NULL;
