-- The outbox and the saga table work as queues: every row is updated as it moves along, and the
-- claim queries walk partial indexes (pending events, due sagas) that collect an entry for each
-- updated row until vacuum removes it. Autovacuum's default trigger (20% of the table dead) means
-- hundreds of thousands of dead entries on a table of millions; the claims then read them all.
-- The load test showed it: an empty claim reading 570 index pages, and the oldest pending event's
-- age creeping from 1 s to 7 s over a 30-minute run. Vacuum these after a fixed number of dead
-- rows instead, whatever the table's size.
ALTER TABLE outbox_event SET (autovacuum_vacuum_scale_factor = 0, autovacuum_vacuum_threshold = 50000);
ALTER TABLE payment_saga SET (autovacuum_vacuum_scale_factor = 0, autovacuum_vacuum_threshold = 50000);
