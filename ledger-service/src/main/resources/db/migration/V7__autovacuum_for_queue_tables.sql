-- The outbox works as a queue: each row is updated once published, and the relay's claim walks a
-- partial index of pending rows that keeps an entry for each until vacuum removes it. With
-- autovacuum's default trigger (20% of the table dead) that is hundreds of thousands of entries
-- on a large table, all read by every claim. Vacuum after a fixed number of dead rows instead.
ALTER TABLE outbox_event SET (autovacuum_vacuum_scale_factor = 0, autovacuum_vacuum_threshold = 50000);
