ALTER TABLE payment
ADD COLUMN idempotency_key VARCHAR(255);

ALTER TABLE payment
ADD CONSTRAINT uk_payment_idempotency_key
UNIQUE (idempotency_key);