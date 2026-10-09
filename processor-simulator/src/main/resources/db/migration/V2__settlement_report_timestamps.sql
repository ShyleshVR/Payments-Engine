-- When money moved, for the daily settlement report (reconciliation reads it).
ALTER TABLE authorizations ADD COLUMN captured_at TIMESTAMP;
ALTER TABLE authorizations ADD COLUMN voided_at TIMESTAMP;

UPDATE authorizations SET captured_at = updated_at WHERE status = 'CAPTURED';
UPDATE authorizations SET voided_at = updated_at WHERE status = 'VOIDED';

CREATE INDEX idx_authorizations_created_at ON authorizations (created_at);
CREATE INDEX idx_authorizations_captured_at ON authorizations (captured_at);
CREATE INDEX idx_authorizations_voided_at ON authorizations (voided_at);
CREATE INDEX idx_refunds_created_at ON refunds (created_at);
