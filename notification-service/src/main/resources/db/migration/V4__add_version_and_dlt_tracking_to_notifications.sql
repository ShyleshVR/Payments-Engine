-- Optimistic lock: a delivery outcome is only written if nobody else changed the row since
-- it was claimed (e.g. another instance re-claimed it after our lease expired).
ALTER TABLE notifications
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

-- Dead letters are published by a relay after the FAILED status has committed, and only
-- marked here once Kafka has acknowledged them.
ALTER TABLE notifications
    ADD COLUMN dlt_published_at TIMESTAMP;

-- Rows that were already FAILED before this migration were dead-lettered by the old inline
-- publisher; don't publish them a second time.
UPDATE notifications
SET dlt_published_at = updated_at
WHERE status = 'FAILED';

CREATE INDEX idx_notifications_dlt_pending
    ON notifications (updated_at)
    WHERE status = 'FAILED' AND dlt_published_at IS NULL;
