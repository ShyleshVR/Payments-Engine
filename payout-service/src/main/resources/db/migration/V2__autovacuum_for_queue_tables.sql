-- The outbox and the saga table work as queues: rows are updated as they move along, and the
-- claim queries walk partial indexes that keep an entry for each updated row until vacuum removes
-- it. Autovacuum's default trigger (20% of the table dead) lets that grow with the table; vacuum
-- after a fixed number of dead rows instead (the same setting as payment-service and the ledger).
ALTER TABLE outbox_event SET (autovacuum_vacuum_scale_factor = 0, autovacuum_vacuum_threshold = 50000);
ALTER TABLE payout_saga SET (autovacuum_vacuum_scale_factor = 0, autovacuum_vacuum_threshold = 50000);
